package com.starburst.sas;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Fake SAS-like JDBC objects (java.lang.reflect.Proxy) reproducing the observed driver quirks:
 * <ul>
 *   <li>getSchemas(catalog, pattern) returns no rows, getSchemas() returns the libraries,</li>
 *   <li>reading a column that is not in the result set throws SQLException("No column of that name."),</li>
 *   <li>getSearchStringEscape() is "" by default.</li>
 * </ul>
 * Only generic library/table names are used.
 */
final class FakeSas {

    static final String MISSING_COLUMN_MESSAGE = "No column of that name.";
    static final String URL = "jdbc:sasiom://sas.example.test:8591";

    static final List<String> LIBRARIES = Arrays.asList("WORK", "SASHELP", "LIB_A", "LIBXA", "SASUSER", "MAPS");

    private FakeSas() {
    }

    /** Counters of delegate calls, to assert which getSchemas variant was used. */
    static final class Calls {
        int getSchemasNoArg;
        int getSchemasWithArgs;
    }

    static ResultSet resultSet(List<String> columns, List<Object[]> rows) {
        return (ResultSet) Proxy.newProxyInstance(FakeSas.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                new InvocationHandler() {
                    int pos = -1;
                    boolean lastNull;

                    @Override
                    public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
                        switch (m.getName()) {
                            case "next":
                                return ++pos < rows.size();
                            case "close":
                                return null;
                            case "wasNull":
                                return lastNull;
                            case "getString":
                            case "getObject": {
                                Object v = value(a[0]);
                                return v == null ? null : (m.getName().equals("getString") ? String.valueOf(v) : v);
                            }
                            case "getInt": {
                                Object v = value(a[0]);
                                return v == null ? 0 : ((Number) v).intValue();
                            }
                            case "getShort": {
                                Object v = value(a[0]);
                                return v == null ? (short) 0 : ((Number) v).shortValue();
                            }
                            case "getLong": {
                                Object v = value(a[0]);
                                return v == null ? 0L : ((Number) v).longValue();
                            }
                            case "toString":
                                return "FakeResultSet";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == a[0];
                            default:
                                throw new UnsupportedOperationException(m.getName());
                        }
                    }

                    private Object value(Object col) throws SQLException {
                        int idx = -1;
                        if (col instanceof Integer) {
                            idx = (Integer) col - 1;
                        } else {
                            for (int i = 0; i < columns.size(); i++) {
                                if (columns.get(i).equalsIgnoreCase((String) col)) {
                                    idx = i;
                                }
                            }
                        }
                        if (idx < 0 || idx >= columns.size()) {
                            throw new SQLException(MISSING_COLUMN_MESSAGE);
                        }
                        Object v = rows.get(pos)[idx];
                        lastNull = v == null;
                        return v;
                    }
                });
    }

    static List<Object[]> rows(Object[]... rows) {
        return new ArrayList<>(Arrays.asList(rows));
    }

    static ResultSet emptyResultSet(String... columns) {
        return resultSet(Arrays.asList(columns), new ArrayList<>());
    }

    /** getSchemas() result: TABLE_SCHEM only (no TABLE_CATALOG), like the SAS driver. */
    static ResultSet schemasNoArg(List<String> libraries) {
        List<Object[]> rows = new ArrayList<>();
        libraries.forEach(l -> rows.add(new Object[]{l}));
        return resultSet(Arrays.asList("TABLE_SCHEM"), rows);
    }

    /** getColumns() result: no SOURCE_DATA_TYPE / IS_AUTOINCREMENT / IS_GENERATEDCOLUMN. */
    static ResultSet columnsResult() {
        return resultSet(
                Arrays.asList("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE"),
                rows(new Object[]{null, "LIB_A", "TAB_1", "COL_X", 12, "VARCHAR", 32},
                        new Object[]{null, "LIB_A", "TAB_1", "COL_Y", 8, "DOUBLE", 8}));
    }

    /** getTables() result without TABLE_CAT. */
    static ResultSet tablesResult() {
        return resultSet(Arrays.asList("TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE"),
                rows(new Object[]{"LIB_A", "TAB_1", "TABLE"}));
    }

    static DatabaseMetaData metaData(Calls calls, String searchEscape) {
        return (DatabaseMetaData) Proxy.newProxyInstance(FakeSas.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                (proxy, m, a) -> {
                    switch (m.getName()) {
                        case "getSchemas":
                            if (a == null || a.length == 0) {
                                calls.getSchemasNoArg++;
                                return schemasNoArg(LIBRARIES);
                            }
                            calls.getSchemasWithArgs++;
                            return emptyResultSet("TABLE_SCHEM");
                        case "getSearchStringEscape":
                            return searchEscape;
                        case "getIdentifierQuoteString":
                            return " ";
                        case "getURL":
                            return URL;
                        case "getColumns":
                            return columnsResult();
                        case "getTables":
                            return tablesResult();
                        case "getCatalogs":
                            return failingResultSet("Stream closed");
                        case "getTypeInfo":
                            return failingResultSet("NO COLUMN named X");
                        case "toString":
                            return "FakeMetaData";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == a[0];
                        default:
                            throw new UnsupportedOperationException(m.getName());
                    }
                });
    }

    /** Result set whose every getter fails with the given message (not a missing-column error unless it says so). */
    static ResultSet failingResultSet(String message) {
        return (ResultSet) Proxy.newProxyInstance(FakeSas.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, m, a) -> {
                    if (m.getName().startsWith("get")) {
                        throw new SQLException(message);
                    }
                    return defaultFor(m.getReturnType(), proxy, m, a);
                });
    }

    private static Object defaultFor(Class<?> type, Object proxy, Method m, Object[] a) {
        switch (m.getName()) {
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == a[0];
            case "toString":
                return "Fake";
            default:
                break;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    static Connection connection(DatabaseMetaData md) {
        return connection(md, new ArrayList<>());
    }

    /** A connection recording the SQL text received by prepareStatement/prepareCall/nativeSQL and Statement methods. */
    static Connection connection(DatabaseMetaData md, List<String> sqlSeen) {
        return (Connection) Proxy.newProxyInstance(FakeSas.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, m, a) -> {
                    switch (m.getName()) {
                        case "getMetaData":
                            return md;
                        case "createStatement":
                            return statement(Statement.class, sqlSeen);
                        case "prepareStatement":
                            sqlSeen.add((String) a[0]);
                            return statement(PreparedStatement.class, sqlSeen);
                        case "prepareCall":
                            sqlSeen.add((String) a[0]);
                            return statement(java.sql.CallableStatement.class, sqlSeen);
                        case "nativeSQL":
                            sqlSeen.add((String) a[0]);
                            return a[0];
                        case "getCatalog":
                        case "close":
                            return null;
                        case "toString":
                            return "FakeConnection";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == a[0];
                        default:
                            throw new UnsupportedOperationException(m.getName());
                    }
                });
    }

    private static Object statement(Class<?> type, List<String> sqlSeen) {
        return Proxy.newProxyInstance(FakeSas.class.getClassLoader(), new Class<?>[]{type},
                (proxy, m, a) -> {
                    if (a != null && a.length > 0 && a[0] instanceof String && !m.getName().startsWith("set")) {
                        sqlSeen.add((String) a[0]);
                    }
                    if (m.getName().equals("executeQuery")) {
                        return resultSet(Arrays.asList("COL_X"), rows(new Object[]{"cell-value-1"}));
                    }
                    return defaultFor(m.getReturnType(), proxy, m, a);
                });
    }

    /** Fake delegate driver. */
    static final class FakeDriver implements Driver {
        private final Connection connection;
        private final SQLException failure;

        FakeDriver(Connection connection, SQLException failure) {
            this.connection = connection;
            this.failure = failure;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (failure != null) {
                throw failure;
            }
            return connection;
        }

        @Override
        public boolean acceptsURL(String url) {
            return true;
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 9;
        }

        @Override
        public int getMinorVersion() {
            return 4;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return null;
        }
    }
}
