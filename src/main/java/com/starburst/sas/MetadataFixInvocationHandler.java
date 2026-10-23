package com.starburst.sas;

import java.lang.reflect.Method;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DatabaseMetaData handler active when {@code sas.wrapper.schemaSource=noarg}.
 *
 * <ul>
 *   <li>{@code getSchemas(String catalog, String schemaPattern)}: the SAS driver
 *       returns 0 rows for it, while the no-arg {@code getSchemas()} works. The call is answered
 *       from the delegate's no-arg {@code getSchemas()}, filtered client-side by the pattern
 *       (JDBC LIKE semantics) and returned as a compliant two-column result set.
 *       The {@code catalog} argument is ignored (SAS exposes no catalogs).</li>
 *   <li>Every ResultSet returned by a DatabaseMetaData method is wrapped so that the whitelisted
 *       optional columns missing from the SAS driver are tolerated, see {@link ResultSetInvocationHandler}.</li>
 * </ul>
 * The no-arg {@code getSchemas()} itself and all other methods are plain passthrough (traced).
 */
class MetadataFixInvocationHandler extends TracingInvocationHandler {

    MetadataFixInvocationHandler(Object target, WrapperConfig config) {
        super(target, config);
    }

    @Override
    protected Object call(Method method, Object[] args, StringBuilder note) throws Throwable {
        if (isSchemasWithArguments(method, args)) {
            return schemasViaNoArg((String) args[1], note);
        }
        return invokeTarget(method, args);
    }

    @Override
    protected Object wrapResult(Method method, Object result) {
        if (result instanceof ResultSet) {
            return JdbcProxies.wrapResultSet((ResultSet) result, config, true);
        }
        return super.wrapResult(method, result);
    }

    private static boolean isSchemasWithArguments(Method method, Object[] args) {
        Class<?>[] p = method.getParameterTypes();
        return method.getName().equals("getSchemas") && p.length == 2
                && p[0] == String.class && p[1] == String.class && args != null && args.length == 2;
    }

    private ResultSet schemasViaNoArg(String schemaPattern, StringBuilder note) throws SQLException {
        DatabaseMetaData md = (DatabaseMetaData) target;
        JdbcLikePattern like = schemaPattern == null ? null : JdbcLikePattern.compile(schemaPattern, searchEscape(md));
        List<String> names = new ArrayList<>();
        int total = 0;
        try (ResultSet rs = md.getSchemas()) {
            while (rs.next()) {
                total++;
                String name = rs.getString("TABLE_SCHEM");
                if (name != null && (like == null || like.matches(name))) {
                    names.add(name);
                }
            }
        }
        Collections.sort(names); // JDBC: ordered by TABLE_CATALOG, TABLE_SCHEM
        note.append("noarg fix: delegate getSchemas() rows=").append(total).append(", returned=").append(names.size());
        return SchemaRowSets.build(names);
    }

    private static String searchEscape(DatabaseMetaData md) {
        try {
            return md.getSearchStringEscape();
        } catch (SQLException e) {
            return null;
        }
    }
}
