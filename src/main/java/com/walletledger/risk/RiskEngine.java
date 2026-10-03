package com.walletledger.risk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates the fraud rules stored in risk_rules. Runs inside the transfer
 * transaction, after the sender's wallet row is locked and before anything is
 * written, so concurrent transfers from the same wallet are counted correctly.
 *
 * <p>Each rule is one indexed query (or none):
 * <pre>
 *  LARGE_AMOUNT     no query - compares the request amount
 *  VELOCITY         ix_transfers_from_created  (from_account_id, created_at)
 *  DAILY_OUTFLOW    ix_transfers_from_created  INCLUDE (amount, status, type) - index-only scan
 *  UNVERIFIED_KYC   users primary key
 *  NEW_PAYEE_LARGE  ix_transfers_from_to       (from_account_id, to_account_id)
 * </pre>
 */
@Component
public class RiskEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RiskEngine(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    record Rule(long id, String code, String action, Long amountThreshold, Integer countThreshold, Integer windowSeconds) {
    }

    public record Hit(long ruleId, String code, String action, Map<String, Object> details) {
    }

    public record Decision(List<Hit> hits, long elapsedMicros) {
        public boolean hold() {
            return hits.stream().anyMatch(h -> "HOLD".equals(h.action()));
        }
    }

    /**
     * @param transferType PEER_TRANSFER or WITHDRAWAL
     * @param senderUserId owner of the sending wallet
     * @param toAccountId  recipient account (wallet for peer transfers, cash-out for withdrawals)
     */
    public Decision evaluate(String transferType, long fromAccountId, long senderUserId, long toAccountId, long amount) {
        long start = System.nanoTime();
        List<Rule> rules = jdbc.query("""
                SELECT id, code, action, amount_threshold, count_threshold, window_seconds
                  FROM risk_rules
                 WHERE enabled AND ? = ANY (applies_to)
                 ORDER BY id
                """, (rs, n) -> new Rule(rs.getLong("id"), rs.getString("code"), rs.getString("action"),
                (Long) rs.getObject("amount_threshold", Long.class),
                (Integer) rs.getObject("count_threshold", Integer.class),
                (Integer) rs.getObject("window_seconds", Integer.class)), transferType);

        List<Hit> hits = new ArrayList<>();
        for (Rule rule : rules) {
            Map<String, Object> details = switch (rule.code()) {
                case "LARGE_AMOUNT" -> largeAmount(rule, amount);
                case "VELOCITY" -> velocity(rule, fromAccountId);
                case "DAILY_OUTFLOW" -> dailyOutflow(rule, fromAccountId, amount);
                case "UNVERIFIED_KYC" -> unverifiedKyc(rule, senderUserId, amount);
                case "NEW_PAYEE_LARGE" -> newPayeeLarge(rule, fromAccountId, toAccountId, amount);
                default -> {
                    log.warn("Unknown risk rule {} ignored", rule.code());
                    yield null;
                }
            };
            if (details != null) {
                hits.add(new Hit(rule.id(), rule.code(), rule.action(), details));
            }
        }
        return new Decision(hits, (System.nanoTime() - start) / 1_000);
    }

    /** Saves one risk_flags row per rule that fired. Called after the transfer row exists. */
    public void recordFlags(long transferId, Decision decision) {
        for (Hit hit : decision.hits()) {
            jdbc.update("INSERT INTO risk_flags (transfer_id, rule_id, action, details) VALUES (?, ?, ?, ?::jsonb)",
                    transferId, hit.ruleId(), hit.action(), toJson(hit.details()));
        }
    }

    private Map<String, Object> largeAmount(Rule rule, long amount) {
        if (rule.amountThreshold() == null || amount < rule.amountThreshold()) {
            return null;
        }
        return details("amount", amount, "threshold", rule.amountThreshold());
    }

    private Map<String, Object> velocity(Rule rule, long fromAccountId) {
        if (rule.countThreshold() == null || rule.windowSeconds() == null) {
            return null;
        }
        Long recent = jdbc.queryForObject("""
                SELECT count(*)
                  FROM transfers
                 WHERE from_account_id = ?
                   AND created_at > now() - make_interval(secs => ?)
                   AND type IN ('PEER_TRANSFER', 'WITHDRAWAL')
                """, Long.class, fromAccountId, rule.windowSeconds());
        // This transfer would be number recent + 1 in the window.
        if (recent + 1 <= rule.countThreshold()) {
            return null;
        }
        return details("transfers_in_window", recent + 1, "threshold", rule.countThreshold(),
                "window_seconds", rule.windowSeconds());
    }

    private Map<String, Object> dailyOutflow(Rule rule, long fromAccountId, long amount) {
        if (rule.amountThreshold() == null || rule.windowSeconds() == null) {
            return null;
        }
        Long sent = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                  FROM transfers
                 WHERE from_account_id = ?
                   AND created_at > now() - make_interval(secs => ?)
                   AND status IN ('POSTED', 'HELD')
                   AND type IN ('PEER_TRANSFER', 'WITHDRAWAL')
                """, Long.class, fromAccountId, rule.windowSeconds());
        if (sent + amount <= rule.amountThreshold()) {
            return null;
        }
        return details("outflow_in_window", sent + amount, "threshold", rule.amountThreshold(),
                "window_seconds", rule.windowSeconds());
    }

    private Map<String, Object> unverifiedKyc(Rule rule, long senderUserId, long amount) {
        if (rule.amountThreshold() == null || amount < rule.amountThreshold()) {
            return null;
        }
        String kyc = jdbc.queryForObject("SELECT kyc_status FROM users WHERE id = ?", String.class, senderUserId);
        if ("VERIFIED".equals(kyc)) {
            return null;
        }
        return details("kyc_status", kyc, "amount", amount, "threshold", rule.amountThreshold());
    }

    private Map<String, Object> newPayeeLarge(Rule rule, long fromAccountId, long toAccountId, long amount) {
        if (rule.amountThreshold() == null || amount < rule.amountThreshold()) {
            return null;
        }
        Boolean paidBefore = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM transfers
                                WHERE from_account_id = ? AND to_account_id = ? AND status = 'POSTED')
                """, Boolean.class, fromAccountId, toAccountId);
        if (Boolean.TRUE.equals(paidBefore)) {
            return null;
        }
        return details("amount", amount, "threshold", rule.amountThreshold(), "first_payment_to_account", toAccountId);
    }

    private static Map<String, Object> details(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
