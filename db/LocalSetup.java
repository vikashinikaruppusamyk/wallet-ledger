import java.io.Console;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Same as db/local-setup.sql, for machines where psql.exe can't run.
 * Asks for the postgres superuser password on the console (never stored or printed).
 *
 *   java -cp <postgresql-jdbc.jar> db/LocalSetup.java
 */
public class LocalSetup {

    public static void main(String[] args) throws Exception {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("Run this from an interactive terminal");
        }
        char[] password = console.readPassword("Password for user postgres: ");
        String base = "jdbc:postgresql://localhost:5432/";

        try (Connection c = DriverManager.getConnection(base + "postgres", "postgres", new String(password));
             Statement s = c.createStatement()) {
            if (!exists(s, "SELECT 1 FROM pg_roles WHERE rolname = 'wallet'")) {
                s.execute("CREATE ROLE wallet LOGIN PASSWORD 'wallet'");
                System.out.println("CREATE ROLE wallet");
            } else {
                System.out.println("role wallet already exists");
            }
            for (String db : new String[]{"wallet", "wallet_test"}) {
                if (!exists(s, "SELECT 1 FROM pg_database WHERE datname = '" + db + "'")) {
                    s.execute("CREATE DATABASE " + db + " OWNER wallet");
                    System.out.println("CREATE DATABASE " + db);
                } else {
                    System.out.println("database " + db + " already exists");
                }
            }
        }
        try (Connection c = DriverManager.getConnection(base + "wallet", "postgres", new String(password));
             Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS dblink");
            System.out.println("CREATE EXTENSION dblink (in database wallet)");
        }
        try (Connection c = DriverManager.getConnection(base + "wallet_test", "wallet", "wallet");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT current_user, current_database()")) {
            rs.next();
            System.out.println("Check OK: connected as " + rs.getString(1) + " to " + rs.getString(2));
        }
        java.util.Arrays.fill(password, ' ');
    }

    private static boolean exists(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            return rs.next();
        }
    }
}
