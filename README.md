# sas-jdbc-wrapper

JDBC driver wrapper for the SAS Integration Technologies JDBC driver (IOM,
`com.sas.rio.MVADriver`, SAS 9.4), meant to sit in front of a Starburst Generic
JDBC catalog (or any JDBC client) in place of the real SAS driver.

> **Support status: best effort, unsupported.** This is a community-grade
> helper, not a Starburst product or a supported component. It is provided as
> is, without SLA, and has only been validated against fakes of the SAS driver
> (no SAS server is available to the maintainers). Use it at your own risk and
> validate it on a non-production environment first.

It does three things:

1. **Traces** every JDBC call made against the real driver (host names and
   credentials redacted, data values hidden by default), through
   `java.util.logging` (logger `com.starburst.sas`).
2. **Works around the JDBC non-conformities of the SAS driver** that make
   schema discovery fail through Starburst's Generic JDBC connector
   (opt-in, `-Dsas.wrapper.schemaSource=noarg`).
3. **Fixes class loading of the CORBA ORB** used by the SAS driver when it runs
   on Trino/Starburst worker threads: every call delegated to the real driver
   runs with the thread context class loader set to the wrapper's own class
   loader (see "Class loading in Trino / Starburst").

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
Starburst) and JUnit 5 (downloaded by Maven) for the tests. No SAS server is
needed to run the tests. The build downloads the glassfish-corba jars from Maven
Central (into `target/corba-libs`, they are not put on the test class path) for
the class loader reproduction tests, see "Tests".

## System properties

All optional, set as JVM properties of the process that loads the driver.

| Property | Default | Description |
|---|---|---|
| `sas.wrapper.delegateClass` | `com.sas.rio.MVADriver` | Real driver class to wrap |
| `sas.wrapper.schemaSource` | `passthrough` | `passthrough`: no behavior change (trace only). `noarg`: enables the fixes below. Any other value (including the removed `catalogs`) is rejected |
| `sas.wrapper.identifierQuoting` | `passthrough` | `passthrough`: SQL unchanged. `strip`: double-quoted identifiers in SQL are rewritten for SAS SQL (see below). Any other value is rejected |
| `sas.wrapper.trace` | `false` | `true` enables call logging (diagnosis): trace lines are logged at `INFO` on logger `com.starburst.sas` **and** written to `sas.wrapper.logFile` (behavior of 0.2.0). `false`: no trace line unless the logger is enabled at `DEBUG` (see "Logging"); the log file is not even created |
| `sas.wrapper.trace.values` | `false` | `false`: `ResultSet` getter results and bound parameters / updated values are logged as `<value>`; `true` logs them (diagnosis only) |
| `sas.wrapper.logFile` | `<tmpdir>/sas-jdbc-wrapper.log` | Only with `sas.wrapper.trace=true`: trace file path (created owner-only when the file system supports POSIX permissions). `none` = no file, `java.util.logging` only |

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

### Installation (no `-Xbootclasspath` needed)

1. On the coordinator **and on every worker**, put these jars together in the
   plugin directory of the Generic JDBC connector (`plugin/generic-jdbc/`):
   * `sas-jdbc-wrapper-<version>.jar` (this project),
   * the SAS driver jars (the ones containing `com.sas.rio.MVADriver`, and the
     other SAS jars it needs),
   * the CORBA jars required by the SAS driver (`org.omg.CORBA` has been removed
     from the JDK since Java 11): e.g. `glassfish-corba-orb`,
     `glassfish-corba-omgapi`, `glassfish-corba-internal-api`,
     `glassfish-corba-csiv2-idl` (4.2.4), `pfl-basic` (5.0.0), `pfl-tf` (4.0.1),
     `management-api` (3.3.0). These are the versions the wrapper's tests use;
     the exact set your SAS driver needs is the one that already works for you.

   **All of them next to the wrapper, in that one directory.** Do not put the
   CORBA jars in the server `lib/` directory as well (see "Class loading").
2. Remove the `-Xbootclasspath/a:...` entries that listed these jars in
   `jvm.config` (the workaround used before 0.3.0), on the coordinator and the
   workers, then restart. Leaving them would keep a second copy of the jars
   outside the plugin directory.
3. Catalog properties:
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
4. JVM properties of the coordinator and the workers (`jvm.config`):
   ```
   -Dsas.wrapper.delegateClass=com.sas.rio.MVADriver
   -Dsas.wrapper.schemaSource=noarg
   -Dsas.wrapper.trace.values=false
   ```
   Add `-Dsas.wrapper.identifierQuoting=strip` if `SELECT` fails on quoted
   identifiers.

### Class loading in Trino / Starburst

The SAS driver initializes a CORBA ORB (`org.omg.CORBA.ORB.init()`, through
`com.sas.iom.orb.ORBBase`). The glassfish-corba ORB looks its implementation
classes up through the **thread context class loader** (TCCL). Trino loads each
connector plugin in its own class loader, which cannot see the application class
loader (its parent is the JVM platform loader). On the worker threads that read
splits, the TCCL is the application class loader, so the ORB does not find the
jars sitting in the plugin directory:

```
org.omg.CORBA.INITIALIZE: can't instantiate default ORB implementation com.sun.corba.ee.impl.orb.ORBSingleton
Caused by: java.lang.ClassNotFoundException: com.sun.corba.ee.impl.orb.ORBSingleton
    at org.omg.CORBA.ORB.create_impl(...)
```

Since 0.3.0, every call delegated to the real driver (`Driver`, `Connection`,
`Statement`, `PreparedStatement`, `ResultSet`, `DatabaseMetaData` and related
proxies) runs with the TCCL set to the class loader that loaded the wrapper (the
plugin class loader), and the previous TCCL is restored in a `finally` block.
Overhead: one TCCL read and one reference comparison per call.

What does **not** work (observed in the reproduction tests, see "Tests"):

* CORBA jars only in the server `lib/` (application class path), none next to
  the wrapper: the plugin class loader cannot see the application class loader,
  so the SAS classes fail with `ClassNotFoundException: org.omg.CORBA.ORB`,
  with or without the wrapper.
* CORBA jars in **both** places (plugin directory and `lib/`), TCCL = application
  loader (any version before 0.3.0): `org.omg.CORBA.INITIALIZE ... caused by
  java.lang.ClassCastException: class com.sun.corba.ee.impl.orb.ORBSingleton`,
  because two distinct `org.omg.CORBA.ORB` classes exist (class identity split).
  With 0.3.0 the plugin copy is used consistently and it works, but keeping a
  single copy in the plugin directory is cleaner.

### Logging

Logging uses `java.util.logging`, logger **`com.starburst.sas`**, so it can be
routed into the server log of the host process.

* Always (one line, `INFO`, at driver load): wrapper version, delegate class,
  `schemaSource`, `identifierQuoting`. It confirms which jar is deployed.
* Per-call trace (SQL text, metadata call arguments, outcome of every delegated
  call, and at connect the active TCCL, the wrapper's class loader and the
  delegate's class loader): enabled by setting the logger to `DEBUG`, in
  `etc/log.properties` of the coordinator **and of every worker** (with the
  Starburst Helm chart, typically the `log.properties` entry of the coordinator and worker
  `etcFiles.properties`; remember that Helm replaces the whole string, so keep
  the existing lines):
  ```
  com.starburst.sas=DEBUG
  ```
  The level is checked at every call; no JVM property is needed. (`DEBUG` is
  `FINE` in `java.util.logging`; the log configuration of the host process is
  what applies, assumed here to follow Trino's `log.properties` semantics.)
* Legacy switch `-Dsas.wrapper.trace=true`: same lines at `INFO`, **plus** the
  file `sas-jdbc-wrapper.log` (or `sas.wrapper.logFile`) as in 0.2.0. Use
  `-Dsas.wrapper.logFile=none` to keep only `java.util.logging`.

Collecting the logs: the lines appear in `server.log` of each node, with the
logger name `com.starburst.sas...`; on Kubernetes e.g.
`kubectl logs <coordinator-or-worker-pod> | grep com.starburst.sas`. The
`ClassNotFoundException` / `ClassCastException` above happens on workers, so
collect from the workers too, not only from the coordinator. When reporting a
problem, send the lines containing `Driver.connect` (they carry the class loader
information) and the stack trace.

### Production: trace off, or values hidden

Tracing is **off** by default. Enable it only while testing, and in production
keep `sas.wrapper.trace=false` and the logger at its default level, or at least
`sas.wrapper.trace.values=false` (the default). With values hidden the trace still contains the SQL text and
the arguments of metadata calls (schema/table name patterns), which is what
makes it useful for diagnosis; treat the log file and the server log lines of the
trace as sensitive (SQL text may contain constants of the queries) and keep them
out of shared locations. JDBC URLs are always logged as
`jdbc:sasiom://<redacted>` (host, port and URL parameters dropped), and the
`Properties` passed to `connect` (credentials) are never logged.

## Usage as a custom driver in a JDBC client (e.g. DBeaver)

1. Database > Driver Manager > New Driver
2. Class Name: `com.starburst.sas.SasJdbcWrapperDriver`
3. Libraries: this jar and the real SAS driver jar
4. Add the JVM properties above (`-Dsas.wrapper...`) to the client's VM arguments if needed.

## Tests

`mvn verify` runs, among unit tests of the wrapper with a fake SAS driver, a class
loader reproduction harness (`PluginLoaderHarness`, `PluginClassLoaderReproTest`)
that needs neither SAS nor Trino: a `URLClassLoader` whose parent is the platform
class loader (like Trino's plugin class loader) holds the wrapper, a fake driver
whose `connect()` calls `org.omg.CORBA.ORB.init()`, and the real glassfish-corba
jars from Maven Central; the call is made on a thread whose TCCL is the JVM
application class loader. It shows the `ClassNotFoundException` without the TCCL
handling, success with it, and the two "jars in `lib/`" variants above. To replay
the scenarios against the classes of another version (e.g. 0.2.0, to see them fail):
`mvn test -Dtest=PluginClassLoaderReproTest -DargLine=-Dsas.test.wrapperClasses=/path/to/classes`.

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
* The TCCL handling and the class loader tests use a fake driver that calls
  `org.omg.CORBA.ORB.init()`: they have not been run against the real SAS driver
  (no SAS server nor SAS jars available). Other class loading needs of the real
  driver, if any, are not covered.
* Best effort, unsupported: see the note at the top.

## `tools/MetadataProbe.java`

Standalone, no wrapper / no Starburst involved: dumps `getCatalogs()`,
`getSchemas()`, `getSchemas(null, null)` and `getTableTypes()` of a driver, to
compare the overloads.

```bash
cd tools && javac MetadataProbe.java
java -cp .:/path/to/driver.jar MetadataProbe <driverClass> <jdbcUrl> <user> <password>
```
