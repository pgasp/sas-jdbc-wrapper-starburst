# sas-jdbc-wrapper

JDBC driver wrapper for the SAS Integration Technologies JDBC driver (IOM —
`com.sas.rio.MVADriver`), meant to sit in front of a Starburst Generic JDBC
catalog (or any JDBC client) in place of the real SAS driver.

## Why

Some SAS IOM environments surface their librefs (SAS's schema-equivalent
concept) inconsistently across `DatabaseMetaData.getSchemas()` vs
`getCatalogs()` depending on the client. A generic JDBC client may work
fine while a specific connector sees no schemas at all. Rather than guess,
this wrapper transparently logs every JDBC call made against the real
driver so the exact call sequence — and what each call actually returns —
is visible.

## Status

- **Phase 1 (current, default behavior)**: pure transparent tracer. No
  behavior change vs. the real driver — every call is logged, then passed
  through unmodified.
- **Phase 2 (scaffolded, not active)**: an extension point
  (`MetadataFixInvocationHandler`) exists to remap specific
  `DatabaseMetaData` methods (e.g. `getSchemas()` -> `getCatalogs()`) once a
  concrete fix is confirmed against a real environment. Nothing is
  implemented there yet — see the `TODO(validated-fix)` in the source.

## Build

```bash
mvn package
# -> target/sas-jdbc-wrapper-<version>.jar
```

## Usage — as a Starburst Generic JDBC catalog

1. Copy `target/sas-jdbc-wrapper-<version>.jar` into the same classpath as
   the real SAS JDBC driver jar.
2. In the catalog properties, replace:
   ```
   "generic-jdbc.driver-class"='com.sas.rio.MVADriver'
   ```
   with:
   ```
   "generic-jdbc.driver-class"='com.starburst.sas.SasJdbcWrapperDriver'
   ```
   Everything else (connection-url, user, password) stays the same.
3. Query as usual. Check the trace log (default:
   `<tmpdir>/sas-jdbc-wrapper.log`, override with
   `-Dsas.wrapper.logFile=...`).

## Usage — as a custom driver in a JDBC client (e.g. DBeaver)

Since this wrapper is a transparent passthrough by default, it works
identically as a "custom driver" in any JDBC client:

1. Database > Driver Manager > New Driver
2. Class Name: `com.starburst.sas.SasJdbcWrapperDriver`
3. Libraries: add both this jar and the real SAS driver jar
4. Connect exactly as before — behavior is unchanged, but every call is
   now logged

This is useful to capture the call sequence that **works** in a client
known to succeed, for comparison against a connector that doesn't.

## System properties

| Property | Default | Description |
|---|---|---|
| `sas.wrapper.delegateClass` | `com.sas.rio.MVADriver` | Real driver class to wrap |
| `sas.wrapper.trace` | `true` | Enable/disable call logging |
| `sas.wrapper.logFile` | `<tmpdir>/sas-jdbc-wrapper.log` | Trace output path |
| `sas.wrapper.schemaSource` | `passthrough` | `catalogs` enables the (currently unimplemented) getSchemas->getCatalogs remap once validated |

## `tools/MetadataProbe.java`

Standalone, no wrapper / no Starburst involved — a 30-second sanity check
to see whether a driver's schema-like objects surface via `getCatalogs()`
or `getSchemas()`, before deciding what (if anything) the wrapper needs to
fix.

```bash
cd tools && javac MetadataProbe.java
java -cp .:/path/to/driver.jar MetadataProbe <driverClass> <jdbcUrl> <user> <password>
```
