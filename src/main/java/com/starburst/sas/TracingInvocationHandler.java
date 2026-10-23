package com.starburst.sas;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Transparent tracer: calls are passed to the target unchanged, the result is
 * wrapped if it is itself a JDBC object, and the call is logged (only when
 * trace is enabled).
 *
 * Subclasses customize behavior through {@link #call} (the actual invocation)
 * and {@link #wrapResult}.
 */
class TracingInvocationHandler implements InvocationHandler {

    static final String VALUE_PLACEHOLDER = "<value>";

    protected final Object target;
    protected final WrapperConfig config;

    TracingInvocationHandler(Object target, WrapperConfig config) {
        this.target = target;
        this.config = config;
    }

    @Override
    public final Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            switch (method.getName()) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                default:
                    // Never delegate toString(): driver objects may print URLs or credentials.
                    return "Traced" + target.getClass().getSimpleName() + "(#" + System.identityHashCode(proxy) + ")";
            }
        }
        StringBuilder note = new StringBuilder();
        Object result = null;
        Throwable error = null;
        try {
            result = wrapResult(method, call(method, args, note));
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            if (config.traceEnabled) {
                config.logCall(target.getClass().getSimpleName() + "." + method.getName(),
                        formatArgs(method, args), formatResult(method, args, result),
                        error == null ? null : Redaction.describeError(error), note.toString());
            }
        }
    }

    /** The real invocation on the target. Overridable. */
    protected Object call(Method method, Object[] args, StringBuilder note) throws Throwable {
        return invokeTarget(method, args);
    }

    protected final Object invokeTarget(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    protected Object wrapResult(Method method, Object result) {
        return JdbcProxies.wrapIfJdbc(result, config);
    }

    // ---- trace formatting ----

    private String formatArgs(Method method, Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        boolean hideFromSecond = !config.traceValues && carriesInputValue(method, args);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(hideFromSecond && i >= 1 ? VALUE_PLACEHOLDER : Redaction.format(args[i]));
        }
        return sb.toString();
    }

    private String formatResult(Method method, Object[] args, Object result) {
        if (!config.traceValues && returnsColumnValue(method, args)) {
            return VALUE_PLACEHOLDER;
        }
        return Redaction.format(result);
    }

    /** setXxx(index|name, value...), updateXxx(index|label, value...), setClientInfo(name, value). */
    private static boolean carriesInputValue(Method method, Object[] args) {
        Class<?> c = method.getDeclaringClass();
        String n = method.getName();
        if (args.length < 2) {
            return false;
        }
        if (c == PreparedStatement.class || c == CallableStatement.class) {
            return n.startsWith("set");
        }
        if (c == ResultSet.class) {
            return n.startsWith("update");
        }
        return c == Connection.class && n.equals("setClientInfo");
    }

    /** getXxx(index|label) on ResultSet / CallableStatement, getClientInfo(name): data values. */
    private static boolean returnsColumnValue(Method method, Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof Integer || args[0] instanceof String)) {
            return false;
        }
        Class<?> c = method.getDeclaringClass();
        String n = method.getName();
        if (c == ResultSet.class || c == CallableStatement.class) {
            return n.startsWith("get");
        }
        return c == Connection.class && n.equals("getClientInfo");
    }
}
