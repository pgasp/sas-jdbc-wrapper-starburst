package com.starburst.sas;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Immutable runtime configuration of the wrapper plus the trace sink.
 *
 * Kept separate from the Driver class so that the proxy machinery can be
 * unit-tested without loading a real SAS driver or touching system
 * properties.
 */
final class WrapperConfig {

    enum SchemaSource {
        /** Default: no behavior change, the real driver is only traced. */
        PASSTHROUGH,
        /**
         * getSchemas(catalog, pattern) is answered from the delegate's no-arg
         * getSchemas(), filtered client-side, and optional metadata columns
         * missing from DatabaseMetaData result sets are tolerated.
         */
        NOARG;

        static SchemaSource parse(String value) {
            if (value == null || value.trim().isEmpty() || "passthrough".equalsIgnoreCase(value.trim())) {
                return PASSTHROUGH;
            }
            if ("noarg".equalsIgnoreCase(value.trim())) {
                return NOARG;
            }
            if ("catalogs".equalsIgnoreCase(value.trim())) {
                throw new IllegalArgumentException("sas.wrapper.schemaSource=catalogs was removed in 0.2.0 "
                        + "(SAS getCatalogs() returns no rows); use 'noarg' or 'passthrough'");
            }
            // Fail closed: an unknown value must not silently leave the fix disabled.
            throw new IllegalArgumentException("Unsupported sas.wrapper.schemaSource value; expected 'passthrough' or 'noarg'");
        }
    }

    enum IdentifierQuoting {
        /** Default: SQL is passed to the driver unchanged. */
        PASSTHROUGH,
        /** Double-quoted identifiers are rewritten for SAS SQL, see {@link SqlIdentifierRewriter}. */
        STRIP;

        static IdentifierQuoting parse(String value) {
            if (value == null || value.trim().isEmpty() || "passthrough".equalsIgnoreCase(value.trim())) {
                return PASSTHROUGH;
            }
            if ("strip".equalsIgnoreCase(value.trim())) {
                return STRIP;
            }
            // Fail closed, like schemaSource.
            throw new IllegalArgumentException("Unsupported sas.wrapper.identifierQuoting value; expected 'passthrough' or 'strip'");
        }
    }

    final SchemaSource schemaSource;
    final IdentifierQuoting identifierQuoting;
    final boolean traceEnabled;
    final boolean traceValues;
    private final Consumer<String> sink;
    private final AtomicLong seq = new AtomicLong();

    WrapperConfig(SchemaSource schemaSource, boolean traceEnabled, boolean traceValues, Consumer<String> sink) {
        this(schemaSource, IdentifierQuoting.PASSTHROUGH, traceEnabled, traceValues, sink);
    }

    WrapperConfig(SchemaSource schemaSource, IdentifierQuoting identifierQuoting, boolean traceEnabled,
            boolean traceValues, Consumer<String> sink) {
        this.schemaSource = schemaSource;
        this.identifierQuoting = identifierQuoting;
        this.traceEnabled = traceEnabled && sink != null;
        this.traceValues = traceValues;
        this.sink = sink;
    }

    static WrapperConfig fromSystemProperties() {
        SchemaSource source = SchemaSource.parse(System.getProperty("sas.wrapper.schemaSource", "passthrough"));
        IdentifierQuoting quoting = IdentifierQuoting.parse(System.getProperty("sas.wrapper.identifierQuoting", "passthrough"));
        boolean trace = Boolean.parseBoolean(System.getProperty("sas.wrapper.trace", "false"));
        boolean values = Boolean.parseBoolean(System.getProperty("sas.wrapper.trace.values", "false"));
        Consumer<String> sink = null;
        if (trace) {
            // Trace off means zero logging: the log file is not even created.
            String defaultLogPath = System.getProperty("java.io.tmpdir") + java.io.File.separator + "sas-jdbc-wrapper.log";
            PrintStream out = openLog(System.getProperty("sas.wrapper.logFile", defaultLogPath));
            sink = out::println;
        }
        return new WrapperConfig(source, quoting, trace, values, sink);
    }

    private static PrintStream openLog(String logPath) {
        try {
            Path path = Paths.get(logPath);
            if (!Files.exists(path)) {
                try {
                    // Owner-only when the file system supports POSIX permissions.
                    Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                    Files.createFile(path, PosixFilePermissions.asFileAttribute(perms));
                } catch (UnsupportedOperationException | IOException ignored) {
                    // fall through: FileOutputStream below creates it (or fails loudly)
                }
            }
            return new PrintStream(new FileOutputStream(logPath, true), true);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open trace log file", e);
        }
    }

    void banner(String delegateClass) {
        if (traceEnabled) {
            sink.accept("=== SasJdbcWrapperDriver loaded, delegate=" + delegateClass
                    + ", trace=true, trace.values=" + traceValues
                    + ", schemaSource=" + schemaSource.name().toLowerCase(Locale.ROOT)
                    + ", identifierQuoting=" + identifierQuoting.name().toLowerCase(Locale.ROOT) + " ===");
        }
    }

    /** Writes one trace line. No-op when trace is disabled. All parameters are already redacted text ({@code error} is null when the call succeeded). */
    void logCall(String label, String args, String result, String error, String note) {
        if (!traceEnabled) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(seq.incrementAndGet()).append("] ").append(System.currentTimeMillis())
                .append(' ').append(label).append('(').append(args).append(')');
        if (error != null) {
            sb.append(" -> THROWS ").append(error);
        } else {
            sb.append(" -> ").append(result);
        }
        if (note != null && !note.isEmpty()) {
            sb.append(" [").append(note).append(']');
        }
        sink.accept(sb.toString());
    }
}
