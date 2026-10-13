import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * JDBC connection helper.
 *
 * Follows the JDBC steps from the lab:
 *   Step 1  Load the JDBC driver        -> Class.forName("oracle.jdbc.OracleDriver")
 *   Step 2  Establish the connection    -> DriverManager.getConnection(url, user, password)
 * Steps 3-6 (create statement, execute SQL, process ResultSet, close) are done
 * in EcomApp / Billing for every request.
 *
 * Connection details are read from db.properties so nothing is hard-coded.
 */
public class DBConnection {

    private static String url;
    private static String user;
    private static String password;

    public static void init(String propertiesFile) throws Exception {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(propertiesFile)) {
            props.load(in);
        }
        String driver = props.getProperty("db.driver", "oracle.jdbc.OracleDriver").trim();
        url      = props.getProperty("db.url").trim();
        user     = props.getProperty("db.user").trim();
        password = props.getProperty("db.password").trim();

        // Step 1: load (register) the JDBC driver class
        Class.forName(driver);
        System.out.println("[JDBC] Driver loaded : " + driver);

        // Test the connection once at start-up so configuration errors show immediately
        try (Connection con = getConnection()) {
            DatabaseMetaData md = con.getMetaData();
            System.out.println("[JDBC] Connected to  : " + md.getDatabaseProductName()
                    + " " + md.getDatabaseProductVersion().split("\n")[0]);
            System.out.println("[JDBC] Driver version: " + md.getDriverName() + " " + md.getDriverVersion());
        }
    }

    /** Step 2: open a new connection (closed automatically by try-with-resources = Step 6). */
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
