package com.starburst.sas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JdbcLikePatternTest {

    private static boolean m(String pattern, String escape, String value) {
        return JdbcLikePattern.compile(pattern, escape).matches(value);
    }

    @Test
    void percentAndUnderscoreWildcards() {
        assertTrue(m("%", "", "WORK"));
        assertTrue(m("%", "", ""));
        assertTrue(m("SAS%", "", "SASHELP"));
        assertFalse(m("SAS%", "", "WORK"));
        assertTrue(m("%HELP", "", "SASHELP"));
        assertTrue(m("S%S%P", "", "SASHELP"));
        assertTrue(m("LIB_A", "", "LIB_A"));
        assertTrue(m("LIB_A", "", "LIBXA"));
        assertFalse(m("LIB_A", "", "LIBA"));
        assertTrue(m("____", "", "WORK"));
        assertFalse(m("___", "", "WORK"));
    }

    @Test
    void exactMatchIsCaseSensitiveAndEmptyPatternMatchesOnlyEmpty() {
        assertTrue(m("WORK", "", "WORK"));
        assertFalse(m("work", "", "WORK"));
        assertFalse(m("", "", "WORK"));
        assertTrue(m("", "", ""));
    }

    @Test
    void escapeMakesWildcardsLiteral() {
        assertTrue(m("LIB\\_A", "\\", "LIB_A"));
        assertFalse(m("LIB\\_A", "\\", "LIBXA"));
        assertTrue(m("A\\%B", "\\", "A%B"));
        assertFalse(m("A\\%B", "\\", "AxxB"));
        assertTrue(m("A\\\\B", "\\", "A\\B"), "escaped escape char is a literal backslash");
        assertTrue(m("A\\", "\\", "A\\"), "dangling escape is literal");
        assertTrue(m("X\\Y", "\\", "XY"), "escape before an ordinary character is ignored");
    }

    @Test
    void backslashIsLiteralWhenNoEscapeIsAvailable() {
        assertTrue(m("A\\B", "", "A\\B"));
        assertTrue(m("A\\B", null, "A\\B"));
        assertFalse(m("LIB\\_A", "", "LIB_A"));
        assertTrue(m("LIB\\_A", "", "LIB\\XA"), "'_' stays a wildcard without an escape");
        // a multi-character "escape" is not a valid LIKE escape: ignored
        assertTrue(m("A\\\\B", "\\\\", "A\\\\B"));
    }

    @Test
    void manyWildcardsDoNotBacktrackExponentially() {
        String value = "A".repeat(5_000) + "B";
        assertFalse(m("%A%A%A%A%A%A%A%A%C", "", value));
        assertTrue(m("%A%A%A%A%A%A%A%A%B", "", value));
    }
}
