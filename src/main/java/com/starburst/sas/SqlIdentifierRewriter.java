package com.starburst.sas;

/**
 * Rewrites double-quoted identifiers of a SQL text for SAS SQL, where double
 * quotes are string constants unless the DQUOTE=ANSI option is set.
 *
 * <ul>
 *   <li>{@code "X"} becomes {@code X} when X is a plain SAS name
 *       ({@code [A-Za-z_][A-Za-z0-9_]{0,31}});</li>
 *   <li>any other name becomes the SAS name literal {@code 'X'n}
 *       (a single quote inside the name is doubled);</li>
 *   <li>{@code ""} inside a quoted identifier stands for one double quote;</li>
 *   <li>single-quoted literals (with {@code ''} escapes), {@code -- line} comments and
 *       block comments are copied untouched, as is everything else (placeholders included).</li>
 * </ul>
 * Hand-written single-pass tokenizer, linear in the input size. Malformed input
 * (unterminated quote, literal or comment, empty identifier) is copied unchanged
 * from the problem on so that the driver reports the error itself.
 */
final class SqlIdentifierRewriter {

    private SqlIdentifierRewriter() {
    }

    static String rewrite(String sql) {
        if (sql == null || sql.indexOf('"') < 0) {
            return sql;
        }
        int n = sql.length();
        StringBuilder out = new StringBuilder(n + 16);
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int end = endOfSingleQuoted(sql, i);
                out.append(sql, i, end);
                i = end;
            } else if (c == '"') {
                int end = i + 1;
                StringBuilder name = new StringBuilder();
                boolean closed = false;
                while (end < n) {
                    char d = sql.charAt(end);
                    if (d == '"') {
                        if (end + 1 < n && sql.charAt(end + 1) == '"') {
                            name.append('"');
                            end += 2;
                            continue;
                        }
                        closed = true;
                        end++;
                        break;
                    }
                    name.append(d);
                    end++;
                }
                if (!closed || name.length() == 0) {
                    out.append(sql, i, n); // malformed: leave the rest to the driver
                    return out.toString();
                }
                appendName(out, name.toString());
                i = end;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? n : end + 1;
                out.append(sql, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                int end = close < 0 ? n : close + 2;
                out.append(sql, i, end);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Index just after the single-quoted literal starting at {@code start} (or the end of input if unterminated). */
    private static int endOfSingleQuoted(String sql, int start) {
        int n = sql.length();
        int i = start + 1;
        while (i < n) {
            if (sql.charAt(i) == '\'') {
                if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static void appendName(StringBuilder out, String name) {
        if (isPlainSasName(name)) {
            out.append(name);
            return;
        }
        out.append('\'');
        for (int k = 0; k < name.length(); k++) {
            char c = name.charAt(k);
            if (c == '\'') {
                out.append('\'');
            }
            out.append(c);
        }
        out.append("'n");
    }

    static boolean isPlainSasName(String s) {
        int len = s.length();
        if (len == 0 || len > 32) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            boolean letterOrUnderscore = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
            boolean digit = c >= '0' && c <= '9';
            if (!(letterOrUnderscore || (digit && i > 0))) {
                return false;
            }
        }
        return true;
    }
}
