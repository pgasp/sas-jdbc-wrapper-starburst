package com.starburst.sas;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formatting of values for the trace log, with host/credential redaction. */
final class Redaction {

    private static final Pattern JDBC_URL = Pattern.compile("(?i)(jdbc:[a-z0-9_.+-]+://)\\S*");

    private Redaction() {
    }

    /**
     * Everything after "jdbc:&lt;subprotocol&gt;://" (host, port, path, URL
     * parameters) is dropped. An URL that does not have that shape is replaced
     * entirely (fail closed).
     */
    static String redactUrl(String url) {
        if (url == null) {
            return "null";
        }
        Matcher m = JDBC_URL.matcher(url);
        if (m.find()) {
            return m.replaceAll("$1<redacted>");
        }
        return "<redacted-url>";
    }

    /** Redacts any JDBC URL embedded in free text (exception messages, toString()...). */
    /** Redacts embedded JDBC URLs and additionally replaces the given sensitive tokens (host, host:port). */
    static String scrubText(String text, String... tokens) {
        if (text == null) {
            return "null";
        }
        String out = JDBC_URL.matcher(text).replaceAll("$1<redacted>");
        for (String token : tokens) {
            if (token != null && !token.isEmpty()) {
                out = out.replace(token, "<redacted>");
            }
        }
        return out;
    }

    /** "SimpleClassName: message" of an exception, with URLs and the given tokens scrubbed. */
    static String describeError(Throwable t, String... tokens) {
        return t.getClass().getSimpleName() + ": " + scrubText(t.getMessage(), tokens);
    }

    /** Tokens to scrub from error messages of a connect attempt: "host:port" first, then "host". */
    static String[] sensitiveTokens(String url) {
        if (url == null) {
            return new String[0];
        }
        Matcher m = Pattern.compile("(?i)jdbc:[a-z0-9_.+-]+://(\\[[^\\]]+\\]|[^:/;?\\s,]+)(?::(\\d+))?").matcher(url);
        if (!m.find()) {
            return new String[0];
        }
        String host = m.group(1);
        String port = m.group(2);
        return port == null ? new String[]{host} : new String[]{host + ":" + port, host};
    }

    /** Formats one argument or return value for the trace. Never prints Properties or JDBC objects' toString(). */
    static String format(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof String) {
            String s = (String) o;
            return s.regionMatches(true, 0, "jdbc:", 0, 5) ? redactUrl(s) : s;
        }
        if (o instanceof Properties) {
            return "<properties>";
        }
        if (o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof Enum) {
            return String.valueOf(o);
        }
        String kind = jdbcKind(o);
        if (kind != null) {
            return kind + "(#" + System.identityHashCode(o) + ")";
        }
        if (o.getClass().isArray()) {
            return o.getClass().getComponentType().getSimpleName() + "[" + java.lang.reflect.Array.getLength(o) + "]";
        }
        return scrubText(String.valueOf(o));
    }

    private static String jdbcKind(Object o) {
        if (o instanceof CallableStatement) {
            return "CallableStatement";
        }
        if (o instanceof PreparedStatement) {
            return "PreparedStatement";
        }
        if (o instanceof Statement) {
            return "Statement";
        }
        if (o instanceof ResultSetMetaData) {
            return "ResultSetMetaData";
        }
        if (o instanceof ResultSet) {
            return "ResultSet";
        }
        if (o instanceof DatabaseMetaData) {
            return "DatabaseMetaData";
        }
        if (o instanceof Connection) {
            return "Connection";
        }
        return null;
    }
}
