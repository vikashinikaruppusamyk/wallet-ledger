package com.walletledger.lab;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Milestone 2, step 1: the two-session isolation lab, scripted.
 *
 * <p>Two real sessions (A and B) run against Postgres side by side, exactly as two psql windows would.
 * Every statement and its result are printed in a psql-like transcript to target/isolation-lab.txt,
 * which is what docs/isolation.md shows. Uses its own throw-away schema, so it never touches the ledger.
 *
 * <pre>./mvnw test -Dlab.exclude=none -Dgroups=lab -Dtest=IsolationLabTest</pre>
 */
@Tag("lab")
class IsolationLabTest {

    private final StringBuilder out = new StringBuilder();

    @Test
    void runLab() throws Exception {
        String url = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/wallet_test");
        String user = System.getenv().getOrDefault("TEST_DB_USER", "wallet");
        String password = System.getenv().getOrDefault("TEST_DB_PASSWORD", "wallet");

        try (Connection a = DriverManager.getConnection(url, user, password);
             Connection b = DriverManager.getConnection(url, user, password)) {
            setUp(a);

            heading("Case 1 - READ COMMITTED (the Postgres default)");
            run(a, "A", "BEGIN;");
            run(a, "A", "SELECT id, balance FROM lab.wallets WHERE id = 1;");
            run(b, "B", "UPDATE lab.wallets SET balance = balance - 100 WHERE id = 1;  -- autocommit");
            run(a, "A", "SELECT id, balance FROM lab.wallets WHERE id = 1;");
            run(a, "A", "COMMIT;");

            heading("Case 2 - REPEATABLE READ");
            run(a, "A", "BEGIN ISOLATION LEVEL REPEATABLE READ;");
            run(a, "A", "SELECT id, balance FROM lab.wallets WHERE id = 1;");
            run(b, "B", "UPDATE lab.wallets SET balance = balance - 100 WHERE id = 1;  -- autocommit");
            run(a, "A", "SELECT id, balance FROM lab.wallets WHERE id = 1;");
            note("A still sees its snapshot. Now A tries to write the row B changed:");
            run(a, "A", "UPDATE lab.wallets SET balance = balance - 50 WHERE id = 1;");
            run(a, "A", "ROLLBACK;");

            heading("Case 3 - SERIALIZABLE: two sessions read, then write based on what they read");
            note("Rule: Asha may withdraw only while her two wallets together hold at least 1000. Both sessions check, both withdraw.");
            run(a, "A", "BEGIN ISOLATION LEVEL SERIALIZABLE;");
            run(b, "B", "BEGIN ISOLATION LEVEL SERIALIZABLE;");
            run(a, "A", "SELECT sum(balance) FROM lab.wallets WHERE owner_id = 1;");
            run(b, "B", "SELECT sum(balance) FROM lab.wallets WHERE owner_id = 1;");
            run(a, "A", "UPDATE lab.wallets SET balance = balance - 700 WHERE id = 1;");
            run(b, "B", "UPDATE lab.wallets SET balance = balance - 700 WHERE id = 2;");
            run(a, "A", "COMMIT;");
            run(b, "B", "COMMIT;");
            run(a, "A", "SELECT id, balance FROM lab.wallets WHERE owner_id = 1 ORDER BY id;");
            note("Same steps under REPEATABLE READ: both commits succeed (write skew) and the rule is broken.");
            reset(a);
            run(a, "A", "BEGIN ISOLATION LEVEL REPEATABLE READ;");
            run(b, "B", "BEGIN ISOLATION LEVEL REPEATABLE READ;");
            run(a, "A", "SELECT sum(balance) FROM lab.wallets WHERE owner_id = 1;");
            run(b, "B", "SELECT sum(balance) FROM lab.wallets WHERE owner_id = 1;");
            run(a, "A", "UPDATE lab.wallets SET balance = balance - 700 WHERE id = 1;");
            run(b, "B", "UPDATE lab.wallets SET balance = balance - 700 WHERE id = 2;");
            run(a, "A", "COMMIT;");
            run(b, "B", "COMMIT;");
            run(a, "A", "SELECT id, balance FROM lab.wallets WHERE owner_id = 1 ORDER BY id;");

            heading("Case 4 - REPEATABLE READ with a join");
            reset(a);
            run(a, "A", "BEGIN ISOLATION LEVEL REPEATABLE READ;");
            run(a, "A", "SELECT o.name, w.id, w.balance FROM lab.wallets w JOIN lab.owners o ON o.id = w.owner_id ORDER BY w.id;");
            run(b, "B", "UPDATE lab.owners SET name = 'Asha K' WHERE id = 1;  -- autocommit");
            run(b, "B", "UPDATE lab.wallets SET balance = balance + 5000 WHERE id = 3;  -- autocommit");
            run(b, "B", "INSERT INTO lab.wallets (id, owner_id, balance) VALUES (4, 2, 42);  -- autocommit");
            run(a, "A", "SELECT o.name, w.id, w.balance FROM lab.wallets w JOIN lab.owners o ON o.id = w.owner_id ORDER BY w.id;");
            run(a, "A", "COMMIT;");
            run(a, "A", "SELECT o.name, w.id, w.balance FROM lab.wallets w JOIN lab.owners o ON o.id = w.owner_id ORDER BY w.id;");

            a.createStatement().execute("DROP SCHEMA lab CASCADE");
        }

        Path file = Path.of("target", "isolation-lab.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString());
        System.out.println(out);
    }

    private void setUp(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS lab CASCADE");
            s.execute("CREATE SCHEMA lab");
            s.execute("CREATE TABLE lab.owners (id BIGINT PRIMARY KEY, name TEXT NOT NULL)");
            s.execute("CREATE TABLE lab.wallets (id BIGINT PRIMARY KEY, owner_id BIGINT NOT NULL REFERENCES lab.owners, balance BIGINT NOT NULL)");
        }
        reset(c);
    }

    private void reset(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("DELETE FROM lab.wallets");
            s.execute("DELETE FROM lab.owners");
            s.execute("INSERT INTO lab.owners VALUES (1, 'Asha'), (2, 'Rahul')");
            s.execute("INSERT INTO lab.wallets VALUES (1, 1, 1000), (2, 1, 500), (3, 2, 300)");
        }
        out.append("\n-- (reset: Asha wallets 1 = 1000, 2 = 500; Rahul wallet 3 = 300)\n");
    }

    private void heading(String text) {
        out.append("\n\n### ").append(text).append("\n");
    }

    private void note(String text) {
        out.append("\n-- ").append(text).append("\n");
    }

    /** Runs one statement in a session and appends "A=> sql" plus psql-style output. */
    private void run(Connection c, String session, String sqlWithComment) {
        String sql = sqlWithComment.replaceAll("\\s*--.*$", "").replaceAll(";$", "");
        out.append("\n").append(session).append("=> ").append(sqlWithComment).append("\n");
        try (Statement s = c.createStatement()) {
            boolean hasRows = s.execute(sql);
            if (hasRows) {
                try (ResultSet rs = s.getResultSet()) {
                    table(rs);
                }
            } else {
                String verb = sql.trim().split("\\s+")[0].toUpperCase();
                out.append(switch (verb) {
                    case "UPDATE", "DELETE" -> verb + " " + s.getUpdateCount();
                    case "INSERT" -> "INSERT 0 " + s.getUpdateCount();
                    default -> verb;
                }).append("\n");
            }
        } catch (SQLException e) {
            out.append("ERROR:  ").append(e.getMessage().replaceFirst("^ERROR: ", "").split("\n")[0])
                    .append("   (SQLSTATE ").append(e.getSQLState()).append(")\n");
        }
    }

    private void table(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String[]> rows = new ArrayList<>();
        String[] head = new String[n];
        int[] width = new int[n];
        for (int i = 0; i < n; i++) {
            head[i] = md.getColumnLabel(i + 1);
            width[i] = head[i].length();
        }
        while (rs.next()) {
            String[] row = new String[n];
            for (int i = 0; i < n; i++) {
                row[i] = String.valueOf(rs.getObject(i + 1));
                width[i] = Math.max(width[i], row[i].length());
            }
            rows.add(row);
        }
        StringBuilder line = new StringBuilder();
        StringBuilder rule = new StringBuilder();
        for (int i = 0; i < n; i++) {
            line.append(i == 0 ? " " : " | ").append(pad(head[i], width[i]));
            rule.append(i == 0 ? "-" : "-+-").append("-".repeat(width[i]));
        }
        out.append(line).append("\n").append(rule).append("-\n");
        for (String[] row : rows) {
            StringBuilder r = new StringBuilder();
            for (int i = 0; i < n; i++) {
                r.append(i == 0 ? " " : " | ").append(pad(row[i], width[i]));
            }
            out.append(r).append("\n");
        }
        out.append("(").append(rows.size()).append(rows.size() == 1 ? " row)\n" : " rows)\n");
    }

    private static String pad(String s, int w) {
        return s + " ".repeat(Math.max(0, w - s.length()));
    }
}
