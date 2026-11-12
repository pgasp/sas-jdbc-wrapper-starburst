package com.starburst.sas;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Stand-in for the SAS IOM driver in the class loader reproduction tests: no SAS code, but it does what the real
 * driver does that matters here, a call to {@code org.omg.CORBA.ORB.init()} (the real one goes through
 * {@code com.sas.iom.orb.ORBBase.getSingletonORB}).
 *
 * <p>The ORB is reached by reflection through the class loader that defined this class, i.e. like compiled code
 * linking against {@code org.omg.CORBA.ORB} would resolve it: the test classpath itself does not contain any CORBA
 * class. The URL selects where the ORB is touched: {@code jdbc:fakecorba://test?at=connect|connection|statement|
 * resultset|metadata} (default {@code connect}); every later JDBC object is a proxy that touches it on its first call.
 */
public class FakeCorbaDriver implements Driver {

    public static final String URL_PREFIX = "jdbc:fakecorba://test";

    /** Calls {@code org.omg.CORBA.ORB.init()}; the exception thrown by the ORB is rethrown as is. */
    static Object touchOrb() throws Throwable {
        Class<?> orb = Class.forName("org.omg.CORBA.ORB", true, FakeCorbaDriver.class.getClassLoader());
        try {
            return orb.getMethod("init").invoke(null);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    private static void touchOrbIf(String at, String where) throws SQLException {
        if (!at.equals(where)) {
            return;
        }
        try {
            touchOrb();
        } catch (SQLException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new SQLException("ORB init failed: " + t, t);
        }
    }

    private static String modeOf(String url) {
        int i = url.indexOf("at=");
        return i < 0 ? "connect" : url.substring(i + 3);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        String at = modeOf(url);
        touchOrbIf(at, "connect");
        return (Connection) proxy(Connection.class, at, "connection", (m, args) -> {
            switch (m.getName()) {
                case "createStatement":
                    return statement(at);
                case "getMetaData":
                    return metaData(at);
                default:
                    return null;
            }
        });
    }

    private static Statement statement(String at) {
        return (Statement) proxy(Statement.class, at, "statement", (m, args) -> {
            if (m.getName().equals("executeQuery")) {
                return resultSet(at);
            }
            return null;
        });
    }

    private static DatabaseMetaData metaData(String at) {
        return (DatabaseMetaData) proxy(DatabaseMetaData.class, at, "metadata", (m, args) -> {
            if (m.getName().equals("getSchemas") && m.getParameterCount() == 0) {
                return resultSet(at);
            }
            return null;
        });
    }

    private static ResultSet resultSet(String at) {
        return (ResultSet) proxy(ResultSet.class, at, "resultset", (m, args) -> {
            if (m.getName().equals("next")) {
                return Boolean.FALSE;
            }
            return null;
        });
    }

    private interface Body {
        Object call(Method m, Object[] args) throws Throwable;
    }

    private static Object proxy(Class<?> type, String at, String where, Body body) {
        InvocationHandler h = (p, m, args) -> {
            if (m.getDeclaringClass() == Object.class) {
                return m.getName().equals("hashCode") ? System.identityHashCode(p) : m.getName().equals("equals") ? p == args[0] : "FakeCorba" + type.getSimpleName();
            }
            if (m.getName().equals("close")) {
                return null;
            }
            touchOrbIf(at, where);
            Object r = body.call(m, args);
            if (r == null && m.getReturnType() == boolean.class) {
                return Boolean.FALSE;
            }
            return r;
        };
        return Proxy.newProxyInstance(FakeCorbaDriver.class.getClassLoader(), new Class<?>[]{type}, h);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
