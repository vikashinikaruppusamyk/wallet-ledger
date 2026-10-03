package com.walletledger.account;

import com.walletledger.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

/**
 * A wallet's ledger entries, newest first.
 *
 * <p>Two pagination modes over the same index
 * {@code ix_ledger_entries_account_created (account_id, created_at DESC, id DESC)}:
 * <ul>
 *   <li>offset: {@code LIMIT n OFFSET k} - Postgres still walks and discards the first k index entries,
 *       so page 5,000 costs 5,000 pages of work.</li>
 *   <li>cursor: {@code WHERE (created_at, id) < (last seen)} - starts the index scan exactly where the
 *       previous page ended, so every page costs the same.</li>
 * </ul>
 */
@Service
public class StatementService {

    public static final int MAX_LIMIT = 100;

    public record StatementEntry(long entryId, long transferId, String transferType, String transferStatus,
                                 String direction, long amount, long counterpartyAccountId, String counterpartyName,
                                 String note, OffsetDateTime createdAt) {
    }

    public record StatementPage(long walletId, List<StatementEntry> entries, int limit, Integer offset, String nextCursor) {
    }

    private static final String SELECT = """
            SELECT e.id, e.transfer_id, t.type, t.status, e.direction, e.amount, e.created_at, t.note,
                   cp.id AS counterparty_account_id,
                   COALESCE(cu.name, cp.name) AS counterparty_name
              FROM ledger_entries e
              JOIN transfers t  ON t.id = e.transfer_id
              JOIN accounts  cp ON cp.id = CASE WHEN e.direction = 'D' THEN t.to_account_id ELSE t.from_account_id END
              LEFT JOIN users cu ON cu.id = cp.user_id
            """;

    private static final RowMapper<StatementEntry> ROW = (rs, n) -> new StatementEntry(
            rs.getLong("id"), rs.getLong("transfer_id"), rs.getString("type"), rs.getString("status"),
            rs.getString("direction"), rs.getLong("amount"), rs.getLong("counterparty_account_id"),
            rs.getString("counterparty_name"), rs.getString("note"), rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public StatementService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public StatementPage page(long walletId, int limit, Integer offset, String cursor) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("VALIDATION_FAILED", "limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset != null && cursor != null) {
            throw ApiException.badRequest("VALIDATION_FAILED", "Use either offset or cursor, not both");
        }

        List<StatementEntry> entries;
        if (cursor != null) {
            Cursor c = Cursor.decode(cursor);
            entries = jdbc.query(SELECT + """
                     WHERE e.account_id = ?
                       AND (e.created_at, e.id) < (?, ?)
                     ORDER BY e.created_at DESC, e.id DESC
                     LIMIT ?
                    """, ROW, walletId, c.createdAt(), c.entryId(), limit);
        } else {
            int skip = offset == null ? 0 : offset;
            if (skip < 0) {
                throw ApiException.badRequest("VALIDATION_FAILED", "offset must be >= 0");
            }
            entries = jdbc.query(SELECT + """
                     WHERE e.account_id = ?
                     ORDER BY e.created_at DESC, e.id DESC
                     LIMIT ? OFFSET ?
                    """, ROW, walletId, limit, skip);
        }

        String next = entries.size() == limit
                ? new Cursor(entries.get(entries.size() - 1).createdAt(), entries.get(entries.size() - 1).entryId()).encode()
                : null;
        return new StatementPage(walletId, entries, limit, cursor == null ? (offset == null ? 0 : offset) : null, next);
    }

    /** Opaque cursor: base64url("epochMicros:entryId"). Postgres timestamps have microsecond precision. */
    record Cursor(OffsetDateTime createdAt, long entryId) {

        String encode() {
            Instant i = createdAt.toInstant();
            long micros = ChronoUnit.MICROS.between(Instant.EPOCH, i);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((micros + ":" + entryId).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String raw) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8).split(":");
                long micros = Long.parseLong(parts[0]);
                Instant instant = Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
                return new Cursor(instant.atOffset(ZoneOffset.UTC), Long.parseLong(parts[1]));
            } catch (RuntimeException e) {
                throw ApiException.badRequest("INVALID_CURSOR", "cursor is not valid");
            }
        }
    }
}
