package com.walletledger.recon;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * "Only one run at a time": each job has a row in job_locks. A run starts with
 * {@code SELECT ... FOR UPDATE NOWAIT} on that row, on a dedicated connection whose
 * transaction stays open for the whole run. A second run gets SQLSTATE 55P03
 * (lock_not_available) immediately and exits. If the process dies, Postgres drops
 * the connection and the lock with it - no stale locks to clean up.
 */
@Component
public class JobLocks {

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final DataSource dataSource;

    public JobLocks(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<Held> tryAcquire(String jobName) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false); // BEGIN - held until Held.close()
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM job_locks WHERE job_name = ? FOR UPDATE NOWAIT")) {
                select.setString(1, jobName);
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("No job_locks row for " + jobName);
                    }
                }
            }
            try (PreparedStatement touch = connection.prepareStatement(
                    "UPDATE job_locks SET last_started_at = now() WHERE job_name = ?")) {
                touch.setString(1, jobName);
                touch.executeUpdate();
            }
            return Optional.of(new Held(connection, jobName));
        } catch (SQLException e) {
            closeQuietly(connection);
            if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                return Optional.empty();
            }
            throw new IllegalStateException("Could not take job lock " + jobName, e);
        } catch (RuntimeException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // connection is being discarded anyway
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // nothing more to do
        }
    }

    /** A held job lock. Closing it records the finish time and commits, which releases the row lock. */
    public static final class Held implements AutoCloseable {

        private final Connection connection;
        private final String jobName;

        private Held(Connection connection, String jobName) {
            this.connection = connection;
            this.jobName = jobName;
        }

        @Override
        public void close() {
            try (PreparedStatement finish = connection.prepareStatement(
                    "UPDATE job_locks SET last_finished_at = clock_timestamp() WHERE job_name = ?")) {
                finish.setString(1, jobName);
                finish.executeUpdate();
                connection.commit(); // COMMIT - releases the lock
            } catch (SQLException e) {
                closeQuietly(connection);
                return;
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // returned to the pool regardless
            }
        }
    }
}
