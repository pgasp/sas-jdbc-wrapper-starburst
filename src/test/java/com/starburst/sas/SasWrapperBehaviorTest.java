package com.starburst.sas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.starburst.sas.WrapperConfig.SchemaSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Behavior of the schema fix and the tolerance whitelist against a fake SAS-like driver. */
class SasWrapperBehaviorTest {

    private final FakeSas.Calls calls = new FakeSas.Calls();
    private final List<String> logLines = new ArrayList<>();

    private DatabaseMetaData wrapped(SchemaSource mode, String escape, boolean trace, boolean values) {
        WrapperConfig cfg = new WrapperConfig(mode, trace, values, logLines::add);
        Connection c = JdbcProxies.wrapConnection(FakeSas.connection(FakeSas.metaData(calls, escape)), cfg);
        try {
            return c.getMetaData();
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    private DatabaseMetaData noarg(String escape) {
        return wrapped(SchemaSource.NOARG, escape, true, false);
    }

    private static List<String> schemaNames(ResultSet rs) throws SQLException {
        List<String> out = new ArrayList<>();
        while (rs.next()) {
            out.add(rs.getString("TABLE_SCHEM"));
        }
        return out;
    }

    // ---- step 1: getSchemas fix ----

    @Test
    void noargReturnsAllSchemasForNullNull() throws Exception {
        List<String> names = schemaNames(noarg("").getSchemas(null, null));
        assertEquals(Arrays.asList("LIBXA", "LIB_A", "MAPS", "SASHELP", "SASUSER", "WORK"), names);
        assertEquals(1, calls.getSchemasNoArg);
        assertEquals(0, calls.getSchemasWithArgs, "the broken two-argument call must not reach the delegate");
    }

    @Test
    void noargIgnoresCatalogArgument() throws Exception {
        assertEquals(6, schemaNames(noarg("").getSchemas("whatever", null)).size());
    }

    @Test
    void noargFiltersByPattern() throws Exception {
        DatabaseMetaData md = noarg("\\");
        assertEquals(Arrays.asList("SASHELP", "SASUSER"), schemaNames(md.getSchemas(null, "SAS%")));
        assertEquals(Arrays.asList("WORK"), schemaNames(md.getSchemas(null, "WORK")));
        assertEquals(Arrays.asList("LIBXA", "LIB_A"), schemaNames(md.getSchemas(null, "LIB_A")), "'_' is a wildcard");
        assertEquals(Arrays.asList("LIB_A"), schemaNames(md.getSchemas(null, "LIB\\_A")), "escaped '_' is literal");
        assertEquals(6, schemaNames(md.getSchemas(null, "%")).size());
        assertEquals(Arrays.asList(), schemaNames(md.getSchemas(null, "NOPE%")));
        assertEquals(Arrays.asList("LIBXA", "LIB_A", "MAPS"), schemaNames(md.getSchemas(null, "%A%")).subList(0, 3));
    }

    @Test
    void emptySearchEscapeMeansBackslashIsLiteral() throws Exception {
        DatabaseMetaData md = noarg("");
        assertEquals(Arrays.asList(), schemaNames(md.getSchemas(null, "LIB\\_A")));
    }

    @Test
    void failingSearchEscapeLookupIsTreatedAsNoEscape() throws Exception {
        // getSearchStringEscape throwing must not break schema listing
        WrapperConfig cfg = new WrapperConfig(SchemaSource.NOARG, true, false, logLines::add);
        DatabaseMetaData md = (DatabaseMetaData) JdbcProxies.wrapIfJdbc(FakeSas.metaData(calls, null), cfg);
        assertEquals(6, schemaNames(md.getSchemas(null, "%")).size());
    }

    @Test
    void resultHasTableSchemAndNullTableCatalog() throws Exception {
        try (ResultSet rs = noarg("").getSchemas(null, "WORK")) {
            ResultSetMetaData rsmd = rs.getMetaData();
            assertEquals(2, rsmd.getColumnCount());
            assertEquals("TABLE_SCHEM", rsmd.getColumnLabel(1));
            assertEquals("TABLE_CATALOG", rsmd.getColumnLabel(2));
            assertEquals(Types.VARCHAR, rsmd.getColumnType(1));
            assertTrue(rs.next());
            assertEquals("WORK", rs.getString("TABLE_SCHEM"));
            assertEquals("WORK", rs.getString(1));
            assertNull(rs.getString("TABLE_CATALOG"));
            assertTrue(rs.wasNull());
            assertFalse(rs.next());
        }
    }

    @Test
    void returnedResultSetIsTraced() throws Exception {
        try (ResultSet rs = noarg("").getSchemas(null, null)) {
            rs.next();
            rs.getString("TABLE_SCHEM");
        }
        String all = String.join("\n", logLines);
        assertTrue(all.contains("getSchemas(null, null)"), all);
        assertTrue(all.contains("noarg fix: delegate getSchemas() rows=6, returned=6"), all);
        assertTrue(all.contains(".getString(TABLE_SCHEM) -> <value>"), all);
    }

    @Test
    void noArgGetSchemasIsLeftUntouched() throws Exception {
        try (ResultSet rs = noarg("").getSchemas()) {
            assertEquals(Arrays.asList("WORK", "SASHELP", "LIB_A", "LIBXA", "SASUSER", "MAPS"), schemaNames(rs));
        }
        assertEquals(1, calls.getSchemasNoArg);
        assertEquals(0, calls.getSchemasWithArgs);
    }

    // ---- step 2: whitelist of optional columns ----

    @Test
    void whitelistedMissingColumnsReadAsNull() throws Exception {
        try (ResultSet rs = noarg("").getColumns(null, "LIB_A", "TAB_1", "%")) {
            assertTrue(rs.next());
            assertNull(rs.getString("SOURCE_DATA_TYPE"));
            assertTrue(rs.wasNull());
            assertEquals(0, rs.getInt("SOURCE_DATA_TYPE"));
            assertTrue(rs.wasNull());
            assertEquals((short) 0, rs.getShort("SOURCE_DATA_TYPE"));
            assertTrue(rs.wasNull());
            assertNull(rs.getObject("SOURCE_DATA_TYPE"));
            assertNull(rs.getString("IS_AUTOINCREMENT"));
            assertNull(rs.getString("IS_GENERATEDCOLUMN"));
            assertNull(rs.getString("is_generatedcolumn"), "labels are case-insensitive");
            assertNull(rs.getString("TABLE_CATALOG"));
            assertTrue(rs.wasNull());
        }
    }

    @Test
    void presentColumnsStillWorkAndWasNullIsResetByLaterReads() throws Exception {
        try (ResultSet rs = noarg("").getColumns(null, "LIB_A", "TAB_1", "%")) {
            assertTrue(rs.next());
            assertNull(rs.getString("SOURCE_DATA_TYPE"));
            assertTrue(rs.wasNull());
            assertEquals("COL_X", rs.getString("COLUMN_NAME"));
            assertFalse(rs.wasNull(), "wasNull must reflect the last read, not the tolerated one");
            assertEquals(12, rs.getInt("DATA_TYPE"));
            assertFalse(rs.wasNull());
            assertNull(rs.getString("TABLE_CAT"), "present but null");
            assertTrue(rs.wasNull());
        }
    }

    @Test
    void tablesResultWithoutTableCatIsTolerated() throws Exception {
        try (ResultSet rs = noarg("").getTables(null, "LIB_A", "%", null)) {
            assertTrue(rs.next());
            assertNull(rs.getString("TABLE_CAT"));
            assertEquals("TAB_1", rs.getString("TABLE_NAME"));
        }
    }

    @Test
    void noArgSchemasResultWithoutTableCatalogIsTolerated() throws Exception {
        try (ResultSet rs = noarg("").getSchemas()) {
            assertTrue(rs.next());
            assertNull(rs.getString("TABLE_CATALOG"));
        }
    }

    @Test
    void nonWhitelistedMissingColumnStillThrows() throws Exception {
        try (ResultSet rs = noarg("").getColumns(null, "LIB_A", "TAB_1", "%")) {
            assertTrue(rs.next());
            SQLException e = assertThrows(SQLException.class, () -> rs.getString("REMARKS"));
            assertEquals(FakeSas.MISSING_COLUMN_MESSAGE, e.getMessage());
            assertThrows(SQLException.class, () -> rs.getInt("NULLABLE"));
            assertThrows(SQLException.class, () -> rs.getObject("NO_SUCH_COLUMN"));
            // whitelisted label but a getter outside the closed set: not absorbed
            assertThrows(SQLException.class, () -> rs.getLong("SOURCE_DATA_TYPE"));
            // by-index access is never tolerated
            assertThrows(SQLException.class, () -> rs.getString(99));
        }
    }

    @Test
    void toleranceDoesNotApplyToStatementResultSets() throws Exception {
        WrapperConfig cfg = new WrapperConfig(SchemaSource.NOARG, true, false, logLines::add);
        Connection c = JdbcProxies.wrapConnection(FakeSas.connection(FakeSas.metaData(calls, "")), cfg);
        try (ResultSet rs = c.createStatement().executeQuery("SELECT 1")) {
            assertTrue(rs.next());
            assertThrows(SQLException.class, () -> rs.getString("TABLE_CAT"));
        }
    }

    // ---- passthrough ----

    @Test
    void passthroughLeavesEverythingUnchanged() throws Exception {
        DatabaseMetaData md = wrapped(SchemaSource.PASSTHROUGH, "", true, false);
        assertEquals(Arrays.asList(), schemaNames(md.getSchemas(null, null)), "driver bug is visible in passthrough");
        assertEquals(1, calls.getSchemasWithArgs);
        assertEquals(0, calls.getSchemasNoArg);

        try (ResultSet rs = md.getSchemas()) {
            assertTrue(rs.next());
            assertThrows(SQLException.class, () -> rs.getString("TABLE_CATALOG"));
        }
        try (ResultSet rs = md.getColumns(null, "LIB_A", "TAB_1", "%")) {
            assertTrue(rs.next());
            assertThrows(SQLException.class, () -> rs.getString("SOURCE_DATA_TYPE"));
        }
        assertEquals("", md.getSearchStringEscape());
        assertEquals(" ", md.getIdentifierQuoteString());
    }

    // ---- escape is never changed ----

    @Test
    void searchStringEscapeIsReturnedUnchangedInEveryMode() throws Exception {
        assertEquals("", noarg("").getSearchStringEscape());
        assertEquals("", wrapped(SchemaSource.PASSTHROUGH, "", true, false).getSearchStringEscape());
        assertEquals("\\", noarg("\\").getSearchStringEscape(), "a real driver value is passed through as well");
    }

    // ---- tolerance only for missing-column errors ----

    @Test
    void otherSqlExceptionsOnWhitelistedLabelsAreRethrown() throws Exception {
        DatabaseMetaData md = noarg("");
        try (ResultSet rs = md.getCatalogs()) {
            SQLException e = assertThrows(SQLException.class, () -> rs.getString("TABLE_CAT"));
            assertEquals("Stream closed", e.getMessage());
            assertThrows(SQLException.class, () -> rs.getInt("SOURCE_DATA_TYPE"));
        }
    }

    @Test
    void missingColumnMessageIsMatchedCaseInsensitively() throws Exception {
        try (ResultSet rs = noarg("").getTypeInfo()) {
            assertNull(rs.getString("TABLE_CAT"));
            assertTrue(rs.wasNull());
        }
        assertTrue(ResultSetInvocationHandler.isMissingColumn(new SQLException("No column of that name.")));
        assertTrue(ResultSetInvocationHandler.isMissingColumn(new SQLException("no COLUMN")));
        assertFalse(ResultSetInvocationHandler.isMissingColumn(new SQLException("Stream closed")));
        assertFalse(ResultSetInvocationHandler.isMissingColumn(new SQLException((String) null)));
    }

    // ---- configuration ----

    @Test
    void schemaSourceParsing() {
        assertEquals(SchemaSource.PASSTHROUGH, SchemaSource.parse(null));
        assertEquals(SchemaSource.PASSTHROUGH, SchemaSource.parse("passthrough"));
        assertEquals(SchemaSource.NOARG, SchemaSource.parse("NoArg"));
        IllegalArgumentException removed = assertThrows(IllegalArgumentException.class, () -> SchemaSource.parse("catalogs"));
        assertTrue(removed.getMessage().contains("removed"));
        assertThrows(IllegalArgumentException.class, () -> SchemaSource.parse("something-else"));
    }
}
