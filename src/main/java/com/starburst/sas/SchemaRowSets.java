package com.starburst.sas;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;

/** Builds the standards-compliant getSchemas() result with JDK classes (CachedRowSet), no hand-rolled ResultSet. */
final class SchemaRowSets {

    private SchemaRowSets() {
    }

    /** Columns TABLE_SCHEM (not null) and TABLE_CATALOG (always null), one row per name, in the given order. */
    static ResultSet build(List<String> schemaNames) throws SQLException {
        RowSetMetaDataImpl md = new RowSetMetaDataImpl();
        md.setColumnCount(2);
        describe(md, 1, "TABLE_SCHEM", ResultSetMetaData.columnNoNulls);
        describe(md, 2, "TABLE_CATALOG", ResultSetMetaData.columnNullable);

        CachedRowSet rows = RowSetProvider.newFactory().createCachedRowSet();
        rows.setMetaData(md);
        for (String name : schemaNames) {
            // CachedRowSet inserts at the cursor position (before the current row): park the cursor
            // after the last row so that rows keep the order of the list (covered by a unit test).
            rows.afterLast();
            rows.moveToInsertRow();
            rows.updateString(1, name);
            rows.updateNull(2);
            rows.insertRow();
            rows.moveToCurrentRow();
        }
        rows.beforeFirst();
        return rows;
    }

    private static void describe(RowSetMetaDataImpl md, int index, String name, int nullable) throws SQLException {
        md.setColumnName(index, name);
        md.setColumnLabel(index, name);
        md.setColumnType(index, Types.VARCHAR);
        md.setColumnTypeName(index, "VARCHAR");
        md.setNullable(index, nullable);
        md.setCatalogName(index, "");
        md.setSchemaName(index, "");
        md.setTableName(index, "");
        md.setColumnDisplaySize(index, 256);
        md.setPrecision(index, 256);
        md.setScale(index, 0);
        md.setSigned(index, false);
        md.setCaseSensitive(index, true);
        md.setSearchable(index, true);
        md.setCurrency(index, false);
        md.setAutoIncrement(index, false);
    }
}
