package com.starburst.sas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.starburst.sas.WrapperConfig.IdentifierQuoting;
import com.starburst.sas.WrapperConfig.SchemaSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Wiring of the identifier rewrite (sas.wrapper.identifierQuoting=strip). */
class IdentifierQuotingTest {

    private static final String QUOTED = "SELECT \"COL_X\", \"my col\" FROM \"LIB_A\".\"TAB_1\" WHERE \"COL_X\" = ? -- \"c\"";
    private static final String REWRITTEN = "SELECT COL_X, 'my col'n FROM LIB_A.TAB_1 WHERE COL_X = ? -- \"c\"";

    private final List<String> sqlSeen = new ArrayList<>();
    private final List<String> logLines = new ArrayList<>();

    private Connection connection(SchemaSource schema, IdentifierQuoting quoting, boolean trace) {
        WrapperConfig cfg = new WrapperConfig(schema, quoting, trace, false, logLines::add);
        return JdbcProxies.wrapConnection(FakeSas.connection(FakeSas.metaData(new FakeSas.Calls(), ""), sqlSeen), cfg);
    }

    @Test
    void stripRewritesAllEntryPoints() throws Exception {
        Connection c = connection(SchemaSource.PASSTHROUGH, IdentifierQuoting.STRIP, false);
        c.prepareStatement(QUOTED);
        c.prepareStatement(QUOTED, Statement.RETURN_GENERATED_KEYS);
        c.prepareCall(QUOTED);
        assertEquals(REWRITTEN, c.nativeSQL(QUOTED));
        Statement st = c.createStatement();
        st.execute(QUOTED);
        st.executeQuery(QUOTED);
        st.executeUpdate(QUOTED);
        st.addBatch(QUOTED);
        assertEquals(8, sqlSeen.size(), sqlSeen.toString());
        for (String sql : sqlSeen) {
            assertEquals(REWRITTEN, sql);
        }
    }

    @Test
    void prepareStatementParametersAreNotTouched() throws Exception {
        Connection c = connection(SchemaSource.PASSTHROUGH, IdentifierQuoting.STRIP, false);
        PreparedStatement ps = c.prepareStatement("SELECT \"A\" FROM T WHERE B = ?");
        ps.setString(1, "\"quoted\" value");
        ps.executeQuery();
        assertEquals(Arrays.asList("SELECT A FROM T WHERE B = ?"), sqlSeen, "setString values are not SQL and are not recorded as such");
    }

    @Test
    void passthroughLeavesSqlUnchanged() throws Exception {
        Connection c = connection(SchemaSource.NOARG, IdentifierQuoting.PASSTHROUGH, false);
        c.prepareStatement(QUOTED);
        c.createStatement().executeQuery(QUOTED);
        assertEquals(Arrays.asList(QUOTED, QUOTED), sqlSeen);
    }

    @Test
    void worksWithNoargModeToo() throws Exception {
        Connection c = connection(SchemaSource.NOARG, IdentifierQuoting.STRIP, false);
        c.prepareStatement(QUOTED);
        assertEquals(Arrays.asList(REWRITTEN), sqlSeen);
    }

    @Test
    void traceShowsTheRewrittenSql() throws Exception {
        Connection c = connection(SchemaSource.PASSTHROUGH, IdentifierQuoting.STRIP, true);
        c.prepareStatement("SELECT \"A\" FROM \"LIB_A\".\"TAB_1\"");
        String log = String.join("\n", logLines);
        assertTrue(log.contains("prepareStatement(SELECT A FROM LIB_A.TAB_1)"), log);
        assertFalse(log.contains("\"LIB_A\""), log);
    }

    @Test
    void parsingFailsClosedOnUnknownValue() {
        assertEquals(IdentifierQuoting.PASSTHROUGH, IdentifierQuoting.parse(null));
        assertEquals(IdentifierQuoting.PASSTHROUGH, IdentifierQuoting.parse("Passthrough"));
        assertEquals(IdentifierQuoting.STRIP, IdentifierQuoting.parse("STRIP"));
        assertThrows(IllegalArgumentException.class, () -> IdentifierQuoting.parse("true"));
        assertThrows(IllegalArgumentException.class, () -> IdentifierQuoting.parse("quote"));
    }

    @Test
    void systemPropertyIsParsedAtLoad() {
        String old = System.getProperty("sas.wrapper.identifierQuoting");
        String oldTrace = System.getProperty("sas.wrapper.trace");
        try {
            System.setProperty("sas.wrapper.trace", "false");
            System.setProperty("sas.wrapper.identifierQuoting", "strip");
            assertEquals(IdentifierQuoting.STRIP, WrapperConfig.fromSystemProperties().identifierQuoting);
            System.setProperty("sas.wrapper.identifierQuoting", "bogus");
            assertThrows(IllegalArgumentException.class, WrapperConfig::fromSystemProperties);
        } finally {
            restore("sas.wrapper.identifierQuoting", old);
            restore("sas.wrapper.trace", oldTrace);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
