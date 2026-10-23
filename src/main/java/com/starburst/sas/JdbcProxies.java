package com.starburst.sas;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Creates the dynamic proxies that wrap the delegate's JDBC objects. */
final class JdbcProxies {

    private static final List<Class<?>> JDBC_INTERFACES = Collections.unmodifiableList(Arrays.asList(
            Connection.class, Statement.class, PreparedStatement.class, CallableStatement.class,
            ResultSet.class, DatabaseMetaData.class, ResultSetMetaData.class));

    private JdbcProxies() {
    }

    /** Wraps the object if it implements any JDBC interface, otherwise returns it untouched. */
    static Object wrapIfJdbc(Object target, WrapperConfig config) {
        if (target == null) {
            return null;
        }
        List<Class<?>> ifaces = interfacesOf(target);
        return ifaces.isEmpty() ? target : proxy(target, ifaces, config);
    }

    /** Wraps a connection returned by the delegate driver (falls back to the Connection interface). */
    static Connection wrapConnection(Connection target, WrapperConfig config) {
        if (target == null) {
            return null;
        }
        List<Class<?>> ifaces = interfacesOf(target);
        if (ifaces.isEmpty()) {
            ifaces.add(Connection.class);
        }
        return (Connection) proxy(target, ifaces, config);
    }

    /**
     * Wraps a ResultSet; when {@code metadataResult} is true (it was returned
     * by a DatabaseMetaData method) and the NOARG mode is active, the
     * whitelisted optional columns are tolerated.
     */
    static ResultSet wrapResultSet(ResultSet target, WrapperConfig config, boolean metadataResult) {
        if (target == null) {
            return null;
        }
        return (ResultSet) Proxy.newProxyInstance(
                JdbcProxies.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                new ResultSetInvocationHandler(target, config,
                        metadataResult && config.schemaSource == WrapperConfig.SchemaSource.NOARG));
    }

    private static Object proxy(Object target, List<Class<?>> ifaces, WrapperConfig config) {
        InvocationHandler handler;
        if (ifaces.contains(DatabaseMetaData.class) && config.schemaSource == WrapperConfig.SchemaSource.NOARG) {
            handler = new MetadataFixInvocationHandler(target, config);
        } else if (ifaces.contains(ResultSet.class)) {
            handler = new ResultSetInvocationHandler(target, config, false);
        } else {
            handler = new TracingInvocationHandler(target, config);
        }
        return Proxy.newProxyInstance(JdbcProxies.class.getClassLoader(), ifaces.toArray(new Class<?>[0]), handler);
    }

    private static List<Class<?>> interfacesOf(Object target) {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> i : JDBC_INTERFACES) {
            if (i.isInstance(target)) {
                out.add(i);
            }
        }
        return out;
    }
}
