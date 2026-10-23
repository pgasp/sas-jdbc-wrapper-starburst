package com.starburst.sas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SqlIdentifierRewriterTest {

    private static String r(String sql) {
        return SqlIdentifierRewriter.rewrite(sql);
    }

    @Test
    void simpleSelectAndQualifiedNames() {
        assertEquals("SELECT COL_X FROM LIB_A.TAB_1 WHERE COL_X = ?",
                r("SELECT \"COL_X\" FROM \"LIB_A\".\"TAB_1\" WHERE \"COL_X\" = ?"));
        assertEquals("SELECT a, B2 FROM WORK.t", r("SELECT \"a\", \"B2\" FROM \"WORK\".\"t\""));
    }

    @Test
    void namesThatAreNotPlainSasNamesBecomeNameLiterals() {
        assertEquals("SELECT 'my col'n FROM LIB_A.TAB_1", r("SELECT \"my col\" FROM \"LIB_A\".\"TAB_1\""));
        assertEquals("SELECT '1st'n FROM T", r("SELECT \"1st\" FROM T"), "leading digit");
        assertEquals("SELECT 'a-b'n FROM T", r("SELECT \"a-b\" FROM T"));
        String longName = "A".repeat(33);
        assertEquals("SELECT '" + longName + "'n FROM T", r("SELECT \"" + longName + "\" FROM T"), "more than 32 chars");
        String maxName = "A".repeat(32);
        assertEquals("SELECT " + maxName + " FROM T", r("SELECT \"" + maxName + "\" FROM T"), "32 chars is still a plain name");
        assertEquals("SELECT 'é'n FROM T", r("SELECT \"é\" FROM T"), "non ASCII");
    }

    @Test
    void embeddedQuotesInIdentifiers() {
        assertEquals("SELECT 'a\"b'n FROM T", r("SELECT \"a\"\"b\" FROM T"), "\"\" is one double quote");
        assertEquals("SELECT 'it''s'n FROM T", r("SELECT \"it's\" FROM T"), "single quote doubled in the literal");
    }

    @Test
    void singleQuotedLiteralsAreUntouched() {
        assertEquals("SELECT COL_X FROM T WHERE COL_X = 'say \"hi\" to \"LIB\"'",
                r("SELECT \"COL_X\" FROM T WHERE \"COL_X\" = 'say \"hi\" to \"LIB\"'"));
        assertEquals("SELECT 'it''s \"x\"' , A FROM T", r("SELECT 'it''s \"x\"' , \"A\" FROM T"), "'' escape inside literal");
        assertEquals("SELECT '' , A FROM T", r("SELECT '' , \"A\" FROM T"), "empty literal");
    }

    @Test
    void commentsAreUntouched() {
        assertEquals("SELECT A -- keep \"this\" 'x\nFROM T", r("SELECT \"A\" -- keep \"this\" 'x\nFROM \"T\""));
        assertEquals("SELECT /* \"no\" ' */ A FROM T", r("SELECT /* \"no\" ' */ \"A\" FROM \"T\""));
        assertEquals("SELECT A FROM T -- trailing \"c\"", r("SELECT \"A\" FROM \"T\" -- trailing \"c\""));
    }

    @Test
    void placeholdersAndOtherTextArePreserved() {
        assertEquals("SELECT A FROM T WHERE B = ? AND C > ? AND D IN (?, ?)",
                r("SELECT \"A\" FROM T WHERE B = ? AND C > ? AND D IN (?, ?)"));
        assertEquals("a - b -c", r("a - b -c"), "single dash is not a comment");
        assertEquals("a / b * c", r("a / b * c"));
    }

    @Test
    void sqlWithoutDoubleQuotesIsReturnedAsIs() {
        String sql = "SELECT A FROM T WHERE B = 'x'";
        assertSame(sql, r(sql));
        assertEquals(null, r(null));
    }

    @Test
    void malformedInputIsLeftToTheDriver() {
        assertEquals("SELECT A FROM \"T", r("SELECT \"A\" FROM \"T"), "unterminated identifier: rest copied unchanged");
        assertEquals("SELECT \"\" FROM T", r("SELECT \"\" FROM T"), "empty identifier unchanged");
        assertEquals("SELECT 'abc \"A\"", r("SELECT 'abc \"A\""), "unterminated literal swallows the rest");
        assertEquals("SELECT /* \"A\"", r("SELECT /* \"A\""), "unterminated comment swallows the rest");
    }

    @Test
    void isPlainSasName() {
        assertTrue(SqlIdentifierRewriter.isPlainSasName("_x1"));
        assertFalse(SqlIdentifierRewriter.isPlainSasName("1x"));
        assertFalse(SqlIdentifierRewriter.isPlainSasName(""));
        assertFalse(SqlIdentifierRewriter.isPlainSasName("a b"));
    }

    @Test
    void linearOnLargeInput() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50_000; i++) {
            sb.append("\"C").append(i).append("\", ");
        }
        String out = r(sb.toString());
        assertTrue(out.startsWith("C0, C1, "));
        assertFalse(out.contains("\""));
    }
}
