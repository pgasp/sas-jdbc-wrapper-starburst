package com.starburst.sas;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * ResultSet tracer. When {@code tolerant} is set (only for result sets that
 * come from a DatabaseMetaData method, in NOARG mode) a read of one of a
 * closed whitelist of optional metadata columns that the delegate rejects
 * with an SQLException saying that the column does not exist is answered with NULL (0 for primitive int/short
 * getters) and {@code wasNull()} reports true until the next read.
 *
 * Any other column keeps throwing: unknown or unexpected columns are a real
 * error (fail closed), only the known SAS non-conformities are absorbed.
 */
class ResultSetInvocationHandler extends TracingInvocationHandler {

    /** Optional JDBC metadata columns the SAS driver does not provide. Closed list. */
    static final Set<String> TOLERATED_COLUMNS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "TABLE_CAT", "TABLE_CATALOG", "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN")));

    private static final Set<String> TOLERATED_GETTERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "getString", "getInt", "getShort", "getObject")));

    private final boolean tolerant;
    private boolean forcedNull;

    ResultSetInvocationHandler(Object target, WrapperConfig config, boolean tolerant) {
        super(target, config);
        this.tolerant = tolerant;
    }

    @Override
    protected Object call(Method method, Object[] args, StringBuilder note) throws Throwable {
        if (method.getName().equals("wasNull")) {
            if (forcedNull) {
                return Boolean.TRUE;
            }
            return invokeTarget(method, args);
        }
        forcedNull = false;
        try {
            return invokeTarget(method, args);
        } catch (SQLException e) {
            if (tolerant && isMissingColumn(e) && isToleratedRead(method, args)) {
                forcedNull = true;
                note.append("missing optional column tolerated");
                return defaultValue(method.getReturnType());
            }
            throw e;
        }
    }

    /** The SAS driver reports a missing column as "No column of that name."; any other SQLException is a real error. */
    static boolean isMissingColumn(SQLException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("no column");
    }

    private static boolean isToleratedRead(Method method, Object[] args) {
        return method.getDeclaringClass() == java.sql.ResultSet.class
                && TOLERATED_GETTERS.contains(method.getName())
                && args != null && args.length >= 1 && args[0] instanceof String
                && TOLERATED_COLUMNS.contains(((String) args[0]).trim().toUpperCase(Locale.ROOT));
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == int.class) {
            return Integer.valueOf(0);
        }
        if (returnType == short.class) {
            return Short.valueOf((short) 0);
        }
        return null;
    }
}
