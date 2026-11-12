package com.starburst.sas;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;

/**
 * Drop-in JDBC driver wrapper for the SAS Integration Technologies JDBC
 * driver (IOM), meant to be used as the driver-class of a Starburst Generic
 * JDBC catalog in place of the real SAS driver.
 *
 * Every JDBC call made against the wrapped driver/connection/statement/
 * metadata/resultset is passed to the real driver through a dynamic proxy
 * and (optionally) traced. In addition, with
 * {@code -Dsas.wrapper.schemaSource=noarg} the known non-conformities of the
 * SAS driver that prevent schema discovery through generic JDBC clients are
 * corrected, see {@link MetadataFixInvocationHandler}.
 *
 * System properties (all optional):
 * <pre>
 *   -Dsas.wrapper.delegateClass=com.sas.rio.MVADriver   real driver class to wrap
 *   -Dsas.wrapper.schemaSource=passthrough|noarg        default passthrough (no behavior change)
 *   -Dsas.wrapper.identifierQuoting=passthrough|strip   default passthrough; strip rewrites "X" identifiers in SQL for SAS
 *   -Dsas.wrapper.trace=true|false                      call logging (INFO + log file); default false. false = no per-call logging
 *   -Dsas.wrapper.trace.values=true|false               default false: ResultSet values and bound parameters are logged as &lt;value&gt;
 *   -Dsas.wrapper.logFile=...|none                      only with trace=true; default: &lt;tmpdir&gt;/sas-jdbc-wrapper.log; none = no file
 * </pre>
 * Logging goes through {@code java.util.logging}, logger {@code com.starburst.sas}: without the system property,
 * per-call trace lines are produced when that logger is enabled at FINE (DEBUG in SEP's {@code log.properties}).
 * Every call delegated to the real driver runs with the thread context class loader set to the loader of this
 * wrapper, see {@link ContextClassLoaders}.
 * JDBC URLs are always logged as {@code jdbc:sasiom://<redacted>} and
 * {@code Properties} (credentials) are never logged.
 */
public class SasJdbcWrapperDriver implements Driver {

    private static final DriverCore core;

    static {
        try {
            WrapperConfig config = WrapperConfig.fromSystemProperties();
            String delegateClass = System.getProperty("sas.wrapper.delegateClass", "com.sas.rio.MVADriver");
            // The delegate's static initializer may already touch the ORB: same TCCL rule as for every other call.
            Driver delegate = ContextClassLoaders.withOwnLoader(
                    () -> newDelegate(delegateClass));
            core = new DriverCore(delegate, config);
            if (config.traceEnabled()) {
                config.banner(delegateClass);
            } else {
                WrapperConfig.LOG.info("SasJdbcWrapperDriver " + version() + " loaded, delegate=" + delegateClass
                        + ", schemaSource=" + config.schemaSource.name().toLowerCase(java.util.Locale.ROOT)
                        + ", identifierQuoting=" + config.identifierQuoting.name().toLowerCase(java.util.Locale.ROOT)
                        + ", tracing off (enable with sas.wrapper.trace=true or logger "
                        + WrapperConfig.LOGGER_NAME + " at DEBUG)");
            }
            DriverManager.registerDriver(new SasJdbcWrapperDriver());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Driver newDelegate(String delegateClass) throws SQLException {
        try {
            return (Driver) Class.forName(delegateClass).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new SQLException("Cannot load delegate driver " + delegateClass + ": " + e, e);
        }
    }

    private static String version() {
        String v = SasJdbcWrapperDriver.class.getPackage().getImplementationVersion();
        return v == null ? "(unknown version)" : v;
    }

    // JDBC spec requires a public no-arg constructor for driver registration.
    public SasJdbcWrapperDriver() {
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return core.acceptsURL(url);
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return core.connect(url, info);
    }

    @Override
    public int getMajorVersion() {
        return core.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return core.getMinorVersion();
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return core.getPropertyInfo(url, info);
    }

    @Override
    public boolean jdbcCompliant() {
        return core.jdbcCompliant();
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
