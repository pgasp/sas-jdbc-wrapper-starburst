package com.starburst.sas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** java.util.logging integration: logger {@code com.starburst.sas}, backward compatibility of the sas.wrapper.* properties. */
class LoggingTest {

    private final Logger logger = Logger.getLogger("com.starburst.sas");
    private final List<LogRecord> records = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private Level oldLevel;
    private boolean oldUseParent;
    private String oldTrace;
    private String oldFile;

    @BeforeEach
    void setUp() {
        oldLevel = logger.getLevel();
        oldUseParent = logger.getUseParentHandlers();
        oldTrace = System.getProperty("sas.wrapper.trace");
        oldFile = System.getProperty("sas.wrapper.logFile");
        System.clearProperty("sas.wrapper.trace");
        System.clearProperty("sas.wrapper.logFile");
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        handler.setLevel(Level.ALL);
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(handler);
        logger.setLevel(oldLevel);
        logger.setUseParentHandlers(oldUseParent);
        restore("sas.wrapper.trace", oldTrace);
        restore("sas.wrapper.logFile", oldFile);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private void connectAndQuery(WrapperConfig cfg) throws Exception {
        java.sql.Connection c = new DriverCore(
                new FakeSas.FakeDriver(FakeSas.connection(FakeSas.metaData(new FakeSas.Calls(), "")), null), cfg)
                .connect(FakeSas.URL, new Properties());
        c.createStatement().executeQuery("SELECT 1").close();
    }

    @Test
    void defaultLevel_noProperty_noTraceLine() throws Exception {
        logger.setLevel(null); // inherit: INFO
        WrapperConfig cfg = WrapperConfig.fromSystemProperties();
        assertFalse(cfg.traceEnabled());
        connectAndQuery(cfg);
        assertTrue(records.isEmpty(), records.toString());
    }

    @Test
    void loggerAtDebug_enablesTrace_atFineLevel_withoutAnySystemProperty() throws Exception {
        logger.setLevel(Level.FINE); // what "com.starburst.sas=DEBUG" in log.properties does
        WrapperConfig cfg = WrapperConfig.fromSystemProperties();
        assertTrue(cfg.traceEnabled());
        connectAndQuery(cfg);
        assertFalse(records.isEmpty());
        assertTrue(records.stream().allMatch(r -> r.getLevel().equals(Level.FINE)), records.toString());
        assertTrue(records.stream().anyMatch(r -> r.getMessage().contains("Driver.connect")));
        assertTrue(records.stream().anyMatch(r -> r.getMessage().contains("executeQuery")));
        // values stay hidden by default, as with the legacy property
        assertFalse(WrapperConfig.fromSystemProperties().traceValues);
    }

    @Test
    void loggerSwitchedAtRuntime_isHonoured() throws Exception {
        logger.setLevel(null);
        WrapperConfig cfg = WrapperConfig.fromSystemProperties();
        assertFalse(cfg.traceEnabled());
        logger.setLevel(Level.FINE);
        assertTrue(cfg.traceEnabled());
    }

    @Test
    void legacyTraceProperty_logsAtInfo_andWritesTheLogFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("legacy.log");
        System.setProperty("sas.wrapper.trace", "true");
        System.setProperty("sas.wrapper.logFile", file.toString());
        logger.setLevel(null); // INFO is enough for the legacy property
        WrapperConfig cfg = WrapperConfig.fromSystemProperties();
        assertTrue(cfg.traceEnabled());
        cfg.logCall("Driver.connect", "jdbc:sasiom://<redacted>", "conn", null, "note");
        assertEquals(1, records.size());
        assertEquals(Level.INFO, records.get(0).getLevel());
        assertTrue(Files.readString(file).contains("Driver.connect"));
    }

    @Test
    void legacyTraceProperty_logFileNone_doesNotCreateAFile(@TempDir Path dir) throws IOException {
        System.setProperty("sas.wrapper.trace", "true");
        System.setProperty("sas.wrapper.logFile", "none");
        WrapperConfig cfg = WrapperConfig.fromSystemProperties();
        cfg.logCall("Driver.connect", "u", "r", null, null);
        assertEquals(1, records.size());
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
        assertFalse(Files.exists(Path.of("none")));
    }

    @Test
    void traceLineWithBracesIsLoggedVerbatim() {
        logger.setLevel(Level.FINE);
        WrapperConfig cfg = WrapperConfig.fromSystemProperties();
        cfg.logCall("Statement.executeQuery", "SELECT '{0}' FROM T", "rs", null, null);
        assertTrue(records.get(0).getMessage().contains("SELECT '{0}' FROM T"));
        assertTrue(records.get(0).getParameters() == null || records.get(0).getParameters().length == 0,
                "no MessageFormat parameters: braces in SQL must not be interpreted");
    }

    @Test
    void connectTraceReportsTheClassLoaders() throws Exception {
        List<String> lines = new ArrayList<>();
        WrapperConfig cfg = new WrapperConfig(WrapperConfig.SchemaSource.PASSTHROUGH, true, false, lines::add);
        connectAndQuery(cfg);
        String connect = lines.stream().filter(l -> l.contains("Driver.connect")).findFirst().orElseThrow();
        assertTrue(connect.contains("tccl="), connect);
        assertTrue(connect.contains("wrapperLoader="), connect);
        assertTrue(connect.contains("delegateLoader="), connect);
    }

    @Test
    void connectTraceNotProducedWhenTraceIsOff() throws Exception {
        List<String> lines = new ArrayList<>();
        WrapperConfig cfg = new WrapperConfig(WrapperConfig.SchemaSource.PASSTHROUGH, false, false, lines::add);
        connectAndQuery(cfg);
        assertTrue(lines.isEmpty(), lines.toString());
    }
}
