# Changelog

Best effort, unsupported component (see README).

## 0.3.0 - 2026-11-12

### Fixed
- **Class loading of the CORBA ORB on Trino/Starburst worker threads.** The SAS driver calls
  `org.omg.CORBA.ORB.init()`, which looks its classes up through the thread context class loader (TCCL). On
  split-runner threads the TCCL is the application class loader, not the plugin class loader, so the glassfish-corba
  jars placed next to the wrapper in `plugin/generic-jdbc/` were invisible
  (`ClassNotFoundException: com.sun.corba.ee.impl.orb.ORBSingleton`). Every call delegated to the real driver
  (`Driver`, `Connection`, `Statement`, `PreparedStatement`, `ResultSet`, `DatabaseMetaData` proxies, and the load of
  the delegate class) now runs with the TCCL set to the wrapper's own class loader, restored in `finally`. The
  `-Xbootclasspath/a:` workaround is no longer needed.

### Changed
- Logging goes through `java.util.logging`, logger `com.starburst.sas` (route it into the server log with
  `com.starburst.sas=DEBUG` in `log.properties`). Existing properties keep working: `sas.wrapper.trace=true` still
  enables tracing (lines now logged at `INFO`, and still written to `sas.wrapper.logFile`); `sas.wrapper.trace.values`
  unchanged. New value `sas.wrapper.logFile=none` (no file). With no property and the logger at its default level,
  nothing is logged per call and no file is created.
- One `INFO` line at driver load (wrapper version, delegate, modes) instead of nothing when tracing is off.
- When tracing, `Driver.connect` logs the active TCCL, the wrapper's class loader and the delegate's class loader.
- The jar manifest carries `Implementation-Version`.

### Tests
- Class loader reproduction harness without SAS and without Trino (`PluginLoaderHarness`,
  `PluginClassLoaderReproTest`): plugin-like `URLClassLoader` (platform parent) holding the wrapper, a fake driver
  calling `ORB.init()` and the real glassfish-corba jars, called from a thread whose TCCL is the application class
  loader. Includes the two "CORBA jars in `lib/`" variants.
- `LoggingTest` for the `java.util.logging` integration.

## 0.2.0 - 2026-10-23

- `sas.wrapper.schemaSource=noarg`: `getSchemas(catalog, pattern)` answered from the no-argument `getSchemas()`;
  optional metadata columns tolerated (closed whitelist). The `catalogs` mode is removed.
- `sas.wrapper.identifierQuoting=passthrough|strip`.
- Trace off by default; URLs redacted, `Properties` never logged, `sas.wrapper.trace.values=false` by default.

## 0.1.0 - 2026-09-17

- Phase 1: transparent JDBC tracer for the SAS IOM driver.
