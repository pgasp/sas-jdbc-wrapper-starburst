package com.starburst.sas;

/**
 * JDBC / SQL LIKE pattern matcher: '%' matches any sequence, '_' any single
 * character, and the search-string escape (when non-empty) makes the next
 * character literal. Hand-written, linear-time two-pointer matcher; the
 * pattern is never turned into a regular expression.
 */
final class JdbcLikePattern {

    private static final byte LITERAL = 0;
    private static final byte ANY_ONE = 1;
    private static final byte ANY_SEQ = 2;

    private final byte[] kinds;
    private final char[] chars;

    private JdbcLikePattern(byte[] kinds, char[] chars) {
        this.kinds = kinds;
        this.chars = chars;
    }

    /**
     * @param escape the DatabaseMetaData search-string escape; null or empty means "no escape character",
     *               a multi-character value is not a valid LIKE escape and is ignored as well.
     */
    static JdbcLikePattern compile(String pattern, String escape) {
        boolean hasEscape = escape != null && escape.length() == 1;
        char esc = hasEscape ? escape.charAt(0) : 0;
        byte[] k = new byte[pattern.length()];
        char[] c = new char[pattern.length()];
        int n = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (hasEscape && ch == esc) {
                if (i + 1 < pattern.length()) {
                    i++;
                    k[n] = LITERAL;
                    c[n++] = pattern.charAt(i);
                } else {
                    k[n] = LITERAL; // dangling escape: literal
                    c[n++] = ch;
                }
            } else if (ch == '%') {
                // collapse consecutive '%'
                if (n == 0 || k[n - 1] != ANY_SEQ) {
                    k[n++] = ANY_SEQ;
                }
            } else if (ch == '_') {
                k[n++] = ANY_ONE;
            } else {
                k[n] = LITERAL;
                c[n++] = ch;
            }
        }
        return new JdbcLikePattern(java.util.Arrays.copyOf(k, n), java.util.Arrays.copyOf(c, n));
    }

    boolean matches(String s) {
        int n = kinds.length;
        int p = 0;
        int i = 0;
        int star = -1;
        int mark = 0;
        while (i < s.length()) {
            if (p < n && (kinds[p] == ANY_ONE || (kinds[p] == LITERAL && chars[p] == s.charAt(i)))) {
                p++;
                i++;
            } else if (p < n && kinds[p] == ANY_SEQ) {
                star = p++;
                mark = i;
            } else if (star >= 0) {
                p = star + 1;
                i = ++mark;
            } else {
                return false;
            }
        }
        while (p < n && kinds[p] == ANY_SEQ) {
            p++;
        }
        return p == n;
    }
}
