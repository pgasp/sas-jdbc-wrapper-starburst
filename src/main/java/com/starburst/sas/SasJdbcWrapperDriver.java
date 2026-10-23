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
 *   -Dsas.wrapper.trace=true|false                      call logging; default true. false = no logging at all
 *   -Dsas.wrapper.trace.values=true|false               default false: ResultSet values and bound parameters are logged as &lt;value&gt;
 *   -Dsas.wrapper.logFile=...                           default: &lt;tmpdir&gt;/sas-jdbc-wrapper.log
 * </pre>
 * JDBC URLs are always logged as {@code jdbc:sasiom://<redacted>} and
 * {@code Properties} (credentials) are never logged.
 */
public class SasJdbcWrapperDriver implements Driver {

    private static final DriverCore core;

    static {
        try {
            WrapperConfig config = WrapperConfig.fromSystemProperties();
            String delegateClass = System.getProperty("sas.wrapper.delegateClass", "com.sas.rio.MVADriver");
            Driver delegate = (Driver) Class.forName(delegateClass).getDeclaredConstructor().newInstance();
            core = new DriverCore(delegate, config);
            config.banner(delegateClass);
            DriverManager.registerDriver(new SasJdbcWrapperDriver());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
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
