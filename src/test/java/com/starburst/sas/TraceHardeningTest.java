package com.starburst.sas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.starburst.sas.WrapperConfig.SchemaSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceHardeningTest {

    private static final String HOST = "sas.example.test";
    private static final String PORT = "8591";
    private static final String DUMMY_CREDENTIAL = "dummy-credential-for-test";

    private final List<String> lines = new ArrayList<>();

    private String log() {
        return String.join("\n", lines);
    }

    private DriverCore core(SchemaSource mode, boolean trace, boolean values, SQLException failure) {
        WrapperConfig cfg = new WrapperConfig(mode, trace, values, lines::add);
        Connection c = FakeSas.connection(FakeSas.metaData(new FakeSas.Calls(), ""));
        return new DriverCore(new FakeSas.FakeDriver(c, failure), cfg);
    }

    private static Properties credentials() {
        Properties p = new Properties();
        p.setProperty("user", "test-user");
        p.setProperty("password", DUMMY_CREDENTIAL);
        return p;
    }

    // ---- URL redaction ----

    @Test
    void redactUrlDropsEverythingAfterTheScheme() {
        assertEquals("jdbc:sasiom://<redacted>", Redaction.redactUrl(FakeSas.URL));
        assertEquals("jdbc:sasiom://<redacted>", Redaction.redactUrl("jdbc:sasiom://" + HOST));
        assertEquals("JDBC:SASIOM://<redacted>", Redaction.redactUrl("JDBC:SASIOM://" + HOST + ":" + PORT + ";k=v?x=y"));
        assertEquals("jdbc:sasiom://<redacted>", Redaction.redactUrl("jdbc:sasiom://[::1]:" + PORT));
        assertEquals("<redacted-url>", Redaction.redactUrl("jdbc:sasiom:" + HOST), "unknown shape is fully redacted");
        assertEquals("null", Redaction.redactUrl(null));
    }

    @Test
    void scrubTextRedactsUrlsAndTokensInFreeText() {
        String msg = "cannot reach " + FakeSas.URL + " (" + HOST + ":" + PORT + ")";
        String out = Redaction.scrubText(msg, Redaction.sensitiveTokens(FakeSas.URL));
        assertFalse(out.contains(HOST) || out.contains(PORT), out);
        assertTrue(out.contains("jdbc:sasiom://<redacted>"), out);
    }

    @Test
    void connectLogsRedactedUrlAndNeverProperties() throws Exception {
        Connection c = core(SchemaSource.PASSTHROUGH, true, false, null).connect(FakeSas.URL, credentials());
        assertTrue(c != null);
        String log = log();
        assertTrue(log.contains("Driver.connect(jdbc:sasiom://<redacted>)"), log);
        assertFalse(log.contains(HOST), log);
        assertFalse(log.contains(PORT), log);
        assertFalse(log.contains(DUMMY_CREDENTIAL), log);
        assertFalse(log.contains("test-user"), log);
    }

    @Test
    void connectFailureMessageIsScrubbed() {
        SQLException failure = new SQLException("Connection refused to " + HOST + ":" + PORT + " for " + FakeSas.URL);
        DriverCore core = core(SchemaSource.NOARG, true, false, failure);
        SQLException thrown = assertThrows(SQLException.class, () -> core.connect(FakeSas.URL, credentials()));
        assertTrue(thrown == failure, "the original exception is rethrown untouched");
        String log = log();
        assertTrue(log.contains("THROWS SQLException"), log);
        assertFalse(log.contains(HOST), log);
        assertFalse(log.contains(PORT), log);
        assertFalse(log.contains(DUMMY_CREDENTIAL), log);
    }

    @Test
    void getUrlIsRedactedInTheLogButReturnedAsIs() throws Exception {
        DriverCore core = core(SchemaSource.NOARG, true, false, null);
        DatabaseMetaData md = core.connect(FakeSas.URL, new Properties()).getMetaData();
        assertEquals(FakeSas.URL, md.getURL(), "callers still get the real URL");
        String log = log();
        assertTrue(log.contains(".getURL() -> jdbc:sasiom://<redacted>"), log);
        assertFalse(log.contains(HOST), log);
    }

    // ---- values ----

    @Test
    void resultSetValuesAreHiddenByDefault() throws Exception {
        DatabaseMetaData md = core(SchemaSource.NOARG, true, false, null).connect(FakeSas.URL, new Properties()).getMetaData();
        try (ResultSet rs = md.getColumns(null, "LIB_A", "TAB_1", "%")) {
            while (rs.next()) {
                rs.getString("COLUMN_NAME");
                rs.getInt("DATA_TYPE");
                rs.getObject(4);
            }
        }
        String log = log();
        assertTrue(log.contains(".getString(COLUMN_NAME) -> <value>"), log);
        assertTrue(log.contains(".getInt(DATA_TYPE) -> <value>"), log);
        assertTrue(log.contains(".getObject(4) -> <value>"), log);
        assertFalse(log.contains("COL_X"), log);
        assertFalse(log.contains("COL_Y"), log);
        // metadata calls and their arguments (needed for diagnosis) stay visible
        assertTrue(log.contains("getColumns(null, LIB_A, TAB_1, %)"), log);
    }

    @Test
    void resultSetValuesAreLoggedWhenExplicitlyEnabled() throws Exception {
        DatabaseMetaData md = core(SchemaSource.NOARG, true, true, null).connect(FakeSas.URL, new Properties()).getMetaData();
        try (ResultSet rs = md.getColumns(null, "LIB_A", "TAB_1", "%")) {
            rs.next();
            rs.getString("COLUMN_NAME");
        }
        assertTrue(log().contains(".getString(COLUMN_NAME) -> COL_X"), log());
    }

    @Test
    void metadataScalarsRemainVisibleWhenValuesAreHidden() throws Exception {
        DatabaseMetaData md = core(SchemaSource.NOARG, true, false, null).connect(FakeSas.URL, new Properties()).getMetaData();
        md.getIdentifierQuoteString();
        assertTrue(log().contains(".getIdentifierQuoteString() -> "), log());
    }

    @Test
    void statementResultsAndBoundParametersAreHiddenByDefault() throws Exception {
        Connection c = core(SchemaSource.PASSTHROUGH, true, false, null).connect(FakeSas.URL, new Properties());
        try (ResultSet rs = c.createStatement().executeQuery("SELECT COL_X FROM LIB_A.TAB_1")) {
            rs.next();
            rs.getString(1);
        }
        PreparedStatement ps = c.prepareStatement("SELECT COL_X FROM LIB_A.TAB_1 WHERE COL_X = ?");
        ps.setString(1, "bound-value-1");
        ps.setObject(1, "bound-value-2");
        String log = log();
        assertFalse(log.contains("cell-value-1"), log);
        assertFalse(log.contains("bound-value"), log);
        assertTrue(log.contains(".setString(1, <value>)"), log);
        assertTrue(log.contains(".getString(1) -> <value>"), log);
    }

    @Test
    void toStringOfProxiesDoesNotDelegate() throws Exception {
        Connection c = core(SchemaSource.PASSTHROUGH, true, false, null).connect(FakeSas.URL, new Properties());
        assertFalse(c.toString().contains("FakeConnection"));
    }

    // ---- trace off = zero logging ----

    @Test
    void traceOffMeansNothingIsLogged() throws Exception {
        List<String> sunk = new ArrayList<>();
        WrapperConfig cfg = new WrapperConfig(SchemaSource.NOARG, false, true, sunk::add);
        Connection c = new DriverCore(new FakeSas.FakeDriver(FakeSas.connection(FakeSas.metaData(new FakeSas.Calls(), "")), null), cfg)
                .connect(FakeSas.URL, credentials());
        DatabaseMetaData md = c.getMetaData();
        md.getSchemas(null, null).close();
        md.getURL();
        c.createStatement().executeQuery("SELECT 1").close();
        cfg.banner("x.Y");
        assertTrue(sunk.isEmpty(), sunk.toString());
        assertTrue(lines.isEmpty());
    }

    @Test
    void traceOffDoesNotEvenCreateTheLogFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("trace-off.log");
        String oldTrace = System.getProperty("sas.wrapper.trace");
        String oldFile = System.getProperty("sas.wrapper.logFile");
        try {
            System.setProperty("sas.wrapper.trace", "false");
            System.setProperty("sas.wrapper.logFile", file.toString());
            WrapperConfig cfg = WrapperConfig.fromSystemProperties();
            assertFalse(cfg.traceEnabled);
            cfg.banner("x.Y");
            assertFalse(Files.exists(file), "no log file must be created when trace is off");

            System.setProperty("sas.wrapper.trace", "true");
            WrapperConfig on = WrapperConfig.fromSystemProperties();
            assertTrue(on.traceEnabled);
            assertFalse(on.traceValues, "values hidden by default");
            on.banner("x.Y");
            assertTrue(Files.readString(file).contains("trace.values=false"));
        } finally {
            restore("sas.wrapper.trace", oldTrace);
            restore("sas.wrapper.logFile", oldFile);
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
