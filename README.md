# sas-jdbc-wrapper

JDBC driver wrapper for the SAS Integration Technologies JDBC driver (IOM,
`com.sas.rio.MVADriver`, SAS 9.4), meant to sit in front of a Starburst Generic
JDBC catalog (or any JDBC client) in place of the real SAS driver.

It does two things:

1. **Traces** every JDBC call made against the real driver (host names and
   credentials redacted, data values hidden by default).
2. **Works around the JDBC non-conformities of the SAS driver** that make
   schema discovery fail through Starburst's Generic JDBC connector
   (opt-in, `-Dsas.wrapper.schemaSource=noarg`).

## Why

The SAS driver behaves differently depending on which `DatabaseMetaData`
overload a client calls, and it does not provide some optional metadata columns:

| Observed behavior of the SAS driver | Effect |
|---|---|
| `getSchemas()` (no argument) returns the libraries (column `TABLE_SCHEM`) | works, e.g. in DBeaver |
| `getSchemas(catalog, schemaPattern)` returns **0 rows** (also with `null, null`) | Starburst calls exactly this (`getSchemas(connection.getCatalog(), null)`), so it sees no schema |
| `getCatalogs()` returns 0 rows | SAS libraries are **not** exposed as catalogs |
| Reading a missing column throws `SQLException("No column of that name.")`: `TABLE_CATALOG` in `getSchemas()` results, `SOURCE_DATA_TYPE`, `IS_AUTOINCREMENT`, `IS_GENERATEDCOLUMN` in `getColumns()` results | generic clients that read these optional JDBC columns fail |
| `Connection.getSchema()` is unsupported, `getIdentifierQuoteString()` returns a space, `getSearchStringEscape()` returns an empty string | not changed by the wrapper (see "Known limitations" and `identifierQuoting`) |

### `catalogs` mode removed

0.1.0 scaffolded `sas.wrapper.schemaSource=catalogs` (remap `getSchemas()` to
`getCatalogs()`). The traces captured against a real SAS IOM server disproved
that hypothesis: `getCatalogs()` returns no rows even in a client that works,
the libraries are only reachable through the no-argument `getSchemas()`. The
mode was therefore removed in 0.2.0, and the value `catalogs` is now rejected
at startup (an unknown value fails the driver load rather than silently
leaving the fix disabled).

## Build

```bash
mvn verify
# -> target/sas-jdbc-wrapper-<version>.jar
```

Requires JDK 11+ at runtime (the schema fix uses `javax.sql.rowset`, i.e. the
JDK module `java.sql.rowset`, which must be present in the JVM that runs
Starburst) and JUnit 5 (downloaded by Maven) for the tests. No SAS server and no
network access are needed to run the tests.

## System properties

All optional, set as JVM properties of the process that loads the driver.

| Property | Default | Description |
|---|---|---|
| `sas.wrapper.delegateClass` | `com.sas.rio.MVADriver` | Real driver class to wrap |
| `sas.wrapper.schemaSource` | `passthrough` | `passthrough`: no behavior change (trace only). `noarg`: enables the fixes below. Any other value (including the removed `catalogs`) is rejected |
| `sas.wrapper.identifierQuoting` | `passthrough` | `passthrough`: SQL unchanged. `strip`: double-quoted identifiers in SQL are rewritten for SAS SQL (see below). Any other value is rejected |
| `sas.wrapper.trace` | `false` | `true` enables call logging (diagnosis). `false` = **no logging at all** (the log file is not even created) |
| `sas.wrapper.trace.values` | `false` | `false`: `ResultSet` getter results and bound parameters / updated values are logged as `<value>`; `true` logs them (diagnosis only) |
| `sas.wrapper.logFile` | `<tmpdir>/sas-jdbc-wrapper.log` | Trace output path (created owner-only when the file system supports POSIX permissions) |

### What `schemaSource=noarg` does

* `DatabaseMetaData.getSchemas(catalog, schemaPattern)` is answered from the
  real driver's no-argument `getSchemas()`: the `TABLE_SCHEM` values are
  filtered client-side with JDBC `LIKE` semantics (`%`, `_`, and the driver's
  `getSearchStringEscape()` when it is non-empty; a `null` pattern means all;
  matching is case-sensitive as in the JDBC specification), sorted, and
  returned as a standards-compliant result set (`TABLE_SCHEM`,
  `TABLE_CATALOG` = `NULL`) built with the JDK's `CachedRowSet`. The `catalog`
  argument is ignored (SAS has no catalogs). The no-argument `getSchemas()` is
  left untouched.
* On result sets returned by `DatabaseMetaData` methods only, reading one of a
  closed list of optional columns that the driver does not provide
  (`TABLE_CAT`, `TABLE_CATALOG`, `SOURCE_DATA_TYPE`, `IS_AUTOINCREMENT`,
  `IS_GENERATEDCOLUMN`) with `getString`, `getInt`, `getShort` or `getObject`
  by label returns `NULL` (`0` for the primitive getters) and `wasNull()`
  reports `true`, **only** when the driver's error says that the column does not
  exist (message containing "no column", case-insensitive; the SAS driver says
  "No column of that name."). Any other `SQLException`, any other column
  (e.g. `REMARKS`) and any read on a result set of a statement keeps throwing
  (fail closed).
* `getSearchStringEscape()` and every other metadata value are never altered
  (the SAS driver returns an empty escape string and that is passed through).

### What `identifierQuoting=strip` does

Starburst's Generic JDBC connector generates SQL with double-quoted
identifiers (`SELECT "COL" FROM "LIB"."TAB" WHERE "COL" = ?`), whereas SAS SQL
treats double quotes as string constants unless the `DQUOTE=ANSI` option is
set. With `strip`, the SQL text passed to `Connection.prepareStatement`,
`prepareCall`, `nativeSQL` and `Statement.execute` / `executeQuery` /
`executeUpdate` / `addBatch` is rewritten by a small linear tokenizer:

* `"X"` becomes `X` when X matches `[A-Za-z_][A-Za-z0-9_]{0,31}`;
* any other name becomes the SAS name literal `'X'n` (`'` doubled inside);
  `""` inside a quoted identifier is one double quote;
* single-quoted literals (including `''` escapes), `--` and `/* */` comments
  and `?` placeholders are never touched;
* malformed input (unterminated quote, literal or comment) is passed to the
  driver unchanged so that it reports the error itself.

The setting is independent of `schemaSource`. Use it if `SELECT` statements
fail on the quoted identifiers; the rewritten SQL is what the trace shows.

## Usage as a Starburst Generic JDBC catalog

1. Put `sas-jdbc-wrapper-<version>.jar` in the same plugin directory / classpath
   as the real SAS driver jars.
2. Catalog properties:
   ```properties
   connection-url=jdbc:sasiom://<sas-host>:<port>
   generic-jdbc.driver-class=com.starburst.sas.SasJdbcWrapperDriver
   case-insensitive-name-matching=true
   generic-jdbc.ignore-search-string-escape=true
   ```
   (user / password / TLS settings unchanged from the working configuration.)
   `case-insensitive-name-matching=true` is recommended: SAS library and table
   names are case-insensitive. `generic-jdbc.ignore-search-string-escape=true`
   is a harmless safeguard: the SAS driver has no search-string escape, so
   metadata lookups must not be escaped.
3. JVM properties of the coordinator and the workers (`jvm.config`):
   ```
   -Dsas.wrapper.delegateClass=com.sas.rio.MVADriver
   -Dsas.wrapper.schemaSource=noarg
   -Dsas.wrapper.trace.values=false
   ```
   Add `-Dsas.wrapper.identifierQuoting=strip` if `SELECT` fails on quoted
   identifiers. Add `-Dsas.wrapper.trace=true [-Dsas.wrapper.logFile=...]` only
   while testing.

### Production: trace off, or values hidden

Tracing is **off** by default. Enable it only while testing, and in production
keep `sas.wrapper.trace=false`, or at least `sas.wrapper.trace.values=false`
(the default). With values hidden the trace still contains the SQL text and
the arguments of metadata calls (schema/table name patterns), which is what
makes it useful for diagnosis; treat the log file as sensitive and keep it
out of shared locations. JDBC URLs are always logged as
`jdbc:sasiom://<redacted>` (host, port and URL parameters dropped), and the
`Properties` passed to `connect` (credentials) are never logged.

## Usage as a custom driver in a JDBC client (e.g. DBeaver)

1. Database > Driver Manager > New Driver
2. Class Name: `com.starburst.sas.SasJdbcWrapperDriver`
3. Libraries: this jar and the real SAS driver jar
4. Add the JVM properties above (`-Dsas.wrapper...`) to the client's VM arguments if needed.

## Security note

The IOM wire protocol is proprietary and, unless encryption is configured on
the SAS side, **credentials and data may travel unencrypted** between the
Starburst nodes and the SAS server. Enable IOM encryption / TLS on the SAS side
(SAS Integration Technologies encryption settings of the server) and use the
matching connection settings. This wrapper does not add
any transport security.

## Known limitations

* Case-sensitive `LIKE` matching for the `getSchemas` pattern, per the JDBC
  specification; names read back from the driver are returned as is.
* The driver has no search-string escape: in metadata lookups by name
  (`getTables`, `getColumns`) an underscore in a schema or table name acts as a
  single-character wildcard, so a name like `LIB_A` can also match `LIBXA`.
* Only JDBC types `VARCHAR` (12) and `DOUBLE` (8) have been observed in
  `getColumns()` results: SAS dates and times appear as numbers (`DOUBLE`) or
  strings (`VARCHAR`), not as JDBC date/time types.
* Whether the IOM driver supports `PreparedStatement` `?` parameters (used by
  Starburst to bind predicate constants) is unverified.
* `Connection.getSchema()` is unsupported by the driver and is not changed.
* `identifierQuoting=strip` does not know about SAS reserved words: a quoted
  identifier that is a plain name is emitted bare.

## `tools/MetadataProbe.java`

Standalone, no wrapper / no Starburst involved: dumps `getCatalogs()`,
`getSchemas()`, `getSchemas(null, null)` and `getTableTypes()` of a driver, to
compare the overloads.

```bash
cd tools && javac MetadataProbe.java
java -cp .:/path/to/driver.jar MetadataProbe <driverClass> <jdbcUrl> <user> <password>
```
