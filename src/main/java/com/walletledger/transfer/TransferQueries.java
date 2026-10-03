package com.walletledger.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletledger.common.ApiException;
import com.walletledger.common.Caller;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/** Read side for transfers: the transfer, its entries, its status history and its risk flags. */
@Component
public class TransferQueries {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TransferQueries(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Entry(long entryId, long accountId, String direction, long amount, OffsetDateTime createdAt) {
    }

    public record StatusChange(String fromStatus, String toStatus, String changedBy, String reason, OffsetDateTime changedAt) {
    }

    public record Flag(long flagId, String rule, String action, JsonNode details, String llmLabel, String llmExplanation) {
    }

    public record TransferView(long transferId, String type, String status, long amount, String currency,
                               long fromAccountId, long toAccountId, Long initiatedBy, Long reversesTransferId,
                               Long parentTransferId, List<Long> childTransferIds, String note,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt,
                               List<Entry> entries, List<StatusChange> statusHistory, List<Flag> riskFlags) {
    }

    public TransferView find(long transferId) {
        List<TransferView> rows = jdbc.query("""
                SELECT id, type, status, amount, from_account_id, to_account_id, initiated_by,
                       reverses_transfer_id, parent_transfer_id, note, created_at, updated_at
                  FROM transfers WHERE id = ?
                """, (rs, n) -> new TransferView(
                rs.getLong("id"), rs.getString("type"), rs.getString("status"), rs.getLong("amount"), "INR",
                rs.getLong("from_account_id"), rs.getLong("to_account_id"),
                (Long) rs.getObject("initiated_by", Long.class),
                (Long) rs.getObject("reverses_transfer_id", Long.class),
                (Long) rs.getObject("parent_transfer_id", Long.class),
                children(transferId), rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                entries(transferId), history(transferId), flags(transferId)), transferId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("TRANSFER_NOT_FOUND", "Transfer " + transferId + " does not exist");
        }
        return rows.get(0);
    }

    /** Admins see everything; a user sees transfers that touch one of their wallets. */
    public void requireVisible(Caller caller, TransferView view) {
        if (caller.admin()) {
            return;
        }
        Integer mine = jdbc.queryForObject(
                "SELECT count(*) FROM accounts WHERE id IN (?, ?) AND user_id = ?",
                Integer.class, view.fromAccountId(), view.toAccountId(), caller.userId());
        if (mine == null || mine == 0) {
            throw ApiException.notFound("TRANSFER_NOT_FOUND", "Transfer " + view.transferId() + " does not exist");
        }
    }

    private List<Long> children(long transferId) {
        return jdbc.queryForList("SELECT id FROM transfers WHERE parent_transfer_id = ? ORDER BY id", Long.class, transferId);
    }

    private List<Entry> entries(long transferId) {
        return jdbc.query("""
                SELECT id, account_id, direction, amount, created_at
                  FROM ledger_entries WHERE transfer_id = ? ORDER BY id
                """, (rs, n) -> new Entry(rs.getLong("id"), rs.getLong("account_id"), rs.getString("direction"),
                rs.getLong("amount"), rs.getObject("created_at", OffsetDateTime.class)), transferId);
    }

    private List<StatusChange> history(long transferId) {
        return jdbc.query("""
                SELECT from_status, to_status, changed_by, reason, changed_at
                  FROM transfer_status_history WHERE transfer_id = ? ORDER BY id
                """, (rs, n) -> new StatusChange(rs.getString("from_status"), rs.getString("to_status"),
                rs.getString("changed_by"), rs.getString("reason"),
                rs.getObject("changed_at", OffsetDateTime.class)), transferId);
    }

    private List<Flag> flags(long transferId) {
        return jdbc.query("""
                SELECT f.id, r.code, f.action, f.details::text AS details, f.llm_label, f.llm_explanation
                  FROM risk_flags f JOIN risk_rules r ON r.id = f.rule_id
                 WHERE f.transfer_id = ? ORDER BY f.id
                """, (rs, n) -> new Flag(rs.getLong("id"), rs.getString("code"), rs.getString("action"),
                readJson(rs.getString("details")), rs.getString("llm_label"), rs.getString("llm_explanation")), transferId);
    }

    private JsonNode readJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
