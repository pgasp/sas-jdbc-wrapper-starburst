package com.starburst.sas;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drop-in JDBC driver wrapper for the SAS Integration Technologies JDBC
 * driver (IOM), meant to be used as the driver-class of a Starburst Generic
 * JDBC catalog in place of the real SAS driver.
 *
 * Phase 1 (current): pure transparent tracer. Every JDBC call made against
 * the wrapped driver/connection/statement/metadata/resultset is logged
 * (method, arguments, return value or exception) via a dynamic proxy, and
 * otherwise passed straight through to the real driver, unmodified. This is
 * used to diagnose schema/catalog discovery mismatches between a working
 * generic JDBC client (e.g. DBeaver) and Starburst's Generic JDBC connector.
 *
 * Phase 2 (extension point, not yet active): once a specific behavioral fix
 * is confirmed against a real environment (e.g. SAS librefs surfacing via
 * DatabaseMetaData.getCatalogs() instead of getSchemas()), it should be
 * implemented in {@link MetadataFixInvocationHandler} and enabled via the
 * sas.wrapper.schemaSource system property. Nothing below implements that
 * fix yet — it is intentionally a no-op passthrough until validated.
 *
 * Also works unmodified as a "custom driver" in a JDBC client (e.g.
 * DBeaver): same class, same delegate, same trace output — useful to
 * capture the call sequence that *works* for comparison against SEP.
 *
 * System properties (all optional):
 *   -Dsas.wrapper.delegateClass=com.sas.rio.MVADriver   (real driver class to wrap)
 *   -Dsas.wrapper.trace=true                            (enable call logging; default true)
 *   -Dsas.wrapper.logFile=...                           (default: <tmpdir>/sas-jdbc-wrapper.log)
 *   -Dsas.wrapper.schemaSource=passthrough|catalogs      (default: passthrough / no behavior change)
 */
public class SasJdbcWrapperDriver implements Driver {

    private static final Driver delegate;
    private static final PrintStream log;
    private static final boolean traceEnabled;
    private static final SchemaSource schemaSource;
    private static final AtomicLong seq = new AtomicLong();

    enum SchemaSource {
        /** Default: don't touch anything, real driver behavior unchanged. */
        PASSTHROUGH,
        /**
         * Experimental / not yet validated: make getSchemas() delegate to
         * getCatalogs() on the real driver. Only enable once confirmed via
         * the trace log that librefs surface as catalogs, not schemas.
         */
        CATALOGS_AS_SCHEMAS
    }

    private static final Set<Class<?>> JDBC_INTERFACES = new HashSet<>(Arrays.asList(
            Connection.class, Statement.class, PreparedStatement.class, CallableStatement.class,
            ResultSet.class, DatabaseMetaData.class, ResultSetMetaData.class
    ));

    static {
        try {
            String delegateClass = System.getProperty("sas.wrapper.delegateClass", "com.sas.rio.MVADriver");
            delegate = (Driver) Class.forName(delegateClass).getDeclaredConstructor().newInstance();

            traceEnabled = Boolean.parseBoolean(System.getProperty("sas.wrapper.trace", "true"));
            String defaultLogPath = System.getProperty("java.io.tmpdir") + File.separator + "sas-jdbc-wrapper.log";
            String logPath = System.getProperty("sas.wrapper.logFile", defaultLogPath);
            log = new PrintStream(new FileOutputStream(logPath, true), true);

            String schemaSourceProp = System.getProperty("sas.wrapper.schemaSource", "passthrough");
            schemaSource = "catalogs".equalsIgnoreCase(schemaSourceProp)
                    ? SchemaSource.CATALOGS_AS_SCHEMAS
                    : SchemaSource.PASSTHROUGH;

            log.println("=== SasJdbcWrapperDriver loaded, delegate=" + delegateClass
                    + ", trace=" + traceEnabled + ", schemaSource=" + schemaSource + " ===");
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
        return delegate.acceptsURL(url);
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        Connection real = null;
        Throwable error = null;
        try {
            real = delegate.connect(url, info);
            return wrap(real, Connection.class);
        } catch (SQLException e) {
            error = e;
            throw e;
        } finally {
            logCall("Driver.connect", new Object[]{url}, real, error);
        }
    }

    @Override
    public int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return delegate.getMinorVersion();
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate.getPropertyInfo(url, info);
    }

    @Override
    public boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    // ---- dynamic proxy wrapping ----

    @SuppressWarnings("unchecked")
    private static <T> T wrap(Object target, Class<T> fallbackIface) {
        if (target == null) {
            return null;
        }
        List<Class<?>> ifaces = new ArrayList<>();
        collectJdbcInterfaces(target.getClass(), ifaces);
        if (ifaces.isEmpty()) {
            ifaces.add(fallbackIface);
        }
        InvocationHandler handler = ifaces.contains(DatabaseMetaData.class) && schemaSource != SchemaSource.PASSTHROUGH
                ? new MetadataFixInvocationHandler(target)
                : new TracingInvocationHandler(target);
        return (T) Proxy.newProxyInstance(
                SasJdbcWrapperDriver.class.getClassLoader(),
                ifaces.toArray(new Class<?>[0]),
                handler
        );
    }

    private static void collectJdbcInterfaces(Class<?> cls, List<Class<?>> out) {
        if (cls == null) {
            return;
        }
        for (Class<?> i : cls.getInterfaces()) {
            if (JDBC_INTERFACES.contains(i) && !out.contains(i)) {
                out.add(i);
            }
        }
        collectJdbcInterfaces(cls.getSuperclass(), out);
    }

    private static void logCall(String label, Object[] args, Object result, Throwable error) {
        if (!traceEnabled) {
            return;
        }
        long n = seq.incrementAndGet();
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(n).append("] ").append(System.currentTimeMillis()).append(' ').append(label).append('(');
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(args[i]);
            }
        }
        sb.append(')');
        if (error != null) {
            sb.append(" -> THROWS ").append(error.getClass().getSimpleName()).append(": ").append(error.getMessage());
        } else {
            sb.append(" -> ").append(describeResult(result));
        }
        log.println(sb);
    }

    private static String describeResult(Object result) {
        if (result == null) {
            return "null";
        }
        if (result instanceof ResultSet) {
            return "ResultSet(#" + System.identityHashCode(result) + ")";
        }
        return String.valueOf(result);
    }

    /** Phase 1: pure passthrough tracer, no behavior change. */
    private static class TracingInvocationHandler implements InvocationHandler {
        private final Object target;

        TracingInvocationHandler(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String label = target.getClass().getSimpleName() + "." + method.getName();
            Object result = null;
            Throwable error = null;
            try {
                result = method.invoke(target, args);
                return wrapIfJdbc(result);
            } catch (InvocationTargetException e) {
                error = e.getTargetException();
                throw error;
            } finally {
                logCall(label, args, result, error);
            }
        }

        Object wrapIfJdbc(Object result) {
            if (result == null) {
                return null;
            }
            for (Class<?> i : result.getClass().getInterfaces()) {
                if (JDBC_INTERFACES.contains(i)) {
                    return wrap(result, i);
                }
            }
            return result;
        }
    }

    /**
     * Phase 2 extension point: same tracing behavior as
     * {@link TracingInvocationHandler}, plus a hook to override specific
     * DatabaseMetaData methods once a fix has been validated. Currently
     * only wired up when sas.wrapper.schemaSource=catalogs; the actual
     * remap of getSchemas() -> getCatalogs() is NOT implemented until
     * confirmed against a real SAS IOM environment (see class javadoc).
     */
    private static class MetadataFixInvocationHandler extends TracingInvocationHandler {
        MetadataFixInvocationHandler(Object target) {
            super(target);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // TODO(validated-fix): once confirmed, branch here on
            // method.getName().equals("getSchemas") and delegate to the
            // real DatabaseMetaData's getCatalogs() instead. Left as a
            // passthrough (super.invoke) until then to avoid shipping an
            // unverified behavior change.
            return super.invoke(proxy, method, args);
        }
    }
}
