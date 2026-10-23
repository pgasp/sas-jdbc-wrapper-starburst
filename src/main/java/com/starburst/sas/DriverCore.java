package com.starburst.sas;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;

/** Driver logic without static state, so it can be tested against a fake delegate. */
final class DriverCore {

    private final Driver delegate;
    private final WrapperConfig config;

    DriverCore(Driver delegate, WrapperConfig config) {
        this.delegate = delegate;
        this.config = config;
    }

    boolean acceptsURL(String url) throws SQLException {
        return delegate.acceptsURL(url);
    }

    Connection connect(String url, Properties info) throws SQLException {
        Connection wrapped = null;
        Throwable error = null;
        try {
            wrapped = JdbcProxies.wrapConnection(delegate.connect(url, info), config);
            return wrapped;
        } catch (SQLException | RuntimeException e) {
            error = e;
            throw e;
        } finally {
            if (config.traceEnabled) {
                // Never log the Properties (credentials) nor host/port of the URL, even in error messages.
                config.logCall("Driver.connect", Redaction.redactUrl(url), Redaction.format(wrapped),
                        error == null ? null : Redaction.describeError(error, Redaction.sensitiveTokens(url)), null);
            }
        }
    }

    int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    int getMinorVersion() {
        return delegate.getMinorVersion();
    }

    DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate.getPropertyInfo(url, info);
    }

    boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }
}
