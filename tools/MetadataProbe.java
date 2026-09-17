import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

/**
 * Standalone sanity check, no Starburst / no wrapper involved. Confirms
 * whether a JDBC driver exposes its schema-like objects via getCatalogs(),
 * getSchemas(), both, or neither -- useful before deciding what a wrapper
 * needs to fix.
 *
 * Usage:
 *   javac MetadataProbe.java
 *   java -cp .:/path/to/driver.jar MetadataProbe \
 *        <driverClass> <jdbcUrl> <user> <password>
 */
public class MetadataProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: MetadataProbe <driverClass> <jdbcUrl> <user> <password>");
            System.exit(1);
        }
        String driverClass = args[0];
        String url = args[1];
        String user = args[2];
        String pass = args[3];

        Class.forName(driverClass);
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            DatabaseMetaData md = conn.getMetaData();
            System.out.println("Driver: " + md.getDriverName() + " " + md.getDriverVersion());

            System.out.println("\n--- getCatalogs() ---");
            dump(md.getCatalogs());

            System.out.println("\n--- getSchemas() ---");
            dump(md.getSchemas());

            System.out.println("\n--- getSchemas(catalog=null, schemaPattern=null) ---");
            dump(md.getSchemas(null, null));

            System.out.println("\n--- getTableTypes() ---");
            dump(md.getTableTypes());
        }
    }

    private static void dump(ResultSet rs) throws Exception {
        ResultSetMetaData rsmd = rs.getMetaData();
        int cols = rsmd.getColumnCount();
        int rows = 0;
        while (rs.next()) {
            StringBuilder sb = new StringBuilder("  ");
            for (int i = 1; i <= cols; i++) {
                if (i > 1) sb.append(" | ");
                sb.append(rsmd.getColumnLabel(i)).append('=').append(rs.getString(i));
            }
            System.out.println(sb);
            rows++;
        }
        if (rows == 0) {
            System.out.println("  (empty)");
        }
    }
}
