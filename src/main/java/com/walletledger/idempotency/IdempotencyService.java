package com.walletledger.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.walletledger.common.ApiException;
import com.walletledger.common.ApiResult;
import com.walletledger.common.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Makes every POST safe to retry.
 *
 * <pre>
 *  Situation                          Response
 *  new key                            run the work, save its response, commit
 *  key seen, same body, completed     the saved status + body, byte for byte
 *  key seen, same body, still running 409 IDEMPOTENCY_KEY_IN_PROGRESS
 *  key seen, different body           422 IDEMPOTENCY_KEY_REUSED, nothing runs
 * </pre>
 *
 * Two modes:
 * <ul>
 *   <li>{@link #executeInTransaction} - the money path. The key row and the transfer are written
 *       in the same READ COMMITTED transaction, so either both exist or neither does.</li>
 *   <li>{@link #executeWithReservation} - for slow work that must not hold a transaction open
 *       (LLM calls, a reconciliation run). The key is committed as IN_PROGRESS first.</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotent-Replayed";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ObjectMapper canonicalMapper;
    private final TransactionTemplate readCommitted;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.canonicalMapper = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.readCommitted = new TransactionTemplate(txManager);
        this.readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.readCommitted.setName("idempotent-request");
    }

    /** One incoming POST. {@code caller} is null only for anonymous endpoints (POST /users). */
    public record Request(Caller caller, String rawKey, String method, String path, JsonNode body) {
    }

    private record Stored(int status, String body, boolean replayed) {
    }

    // -----------------------------------------------------------------------------------------
    // Mode 1: key + money movement in one transaction
    // -----------------------------------------------------------------------------------------

    public ResponseEntity<String> executeInTransaction(Request request, Supplier<ApiResult> work) {
        UUID key = parseKey(request.rawKey());
        String hash = hash(request);
        Long userId = request.caller() == null ? null : request.caller().userId();

        Stored stored = readCommitted.execute(status -> {
            // ---- BEGIN (READ COMMITTED) ----
            setActor(request.caller());

            // 1. Another request with this key still running? Its key row is not committed yet, so we
            //    cannot see it; the transaction-scoped advisory lock is how we notice it.
            Boolean free = jdbc.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class, lockName(userId, key));
            if (!Boolean.TRUE.equals(free)) {
                throw inProgress();
            }

            // 2. Insert the key first. The unique constraint is the real guard: if the row exists,
            //    the earlier request has committed and we only replay (or reject) its response.
            Long keyId = insertKey(userId, key, request, hash);
            if (keyId == null) {
                status.setRollbackOnly();
                return replayOrReject(userId, key, hash);
            }

            // 3. Run the work. A business error (insufficient funds, ...) rolls back everything after
            //    the savepoint, and the error itself becomes the saved response for this key.
            Object savepoint = status.createSavepoint();
            ApiResult outcome;
            try {
                outcome = work.get();
            } catch (ApiException e) {
                if (e.status().is5xxServerError()) {
                    throw e; // not the client's fault: roll back everything, key included, so a retry runs again
                }
                status.rollbackToSavepoint(savepoint);
                outcome = ApiResult.error(e);
            }

            // 4. Save the response on the key row; COMMIT happens when this callback returns.
            String body = toJson(outcome.body());
            complete(keyId, outcome.status(), body);
            return new Stored(outcome.status(), body, false);
            // ---- COMMIT ----
        });
        return toResponse(stored);
    }

    // -----------------------------------------------------------------------------------------
    // Mode 2: reserve the key, run slow work outside any transaction, then complete the key
    // -----------------------------------------------------------------------------------------

    public ResponseEntity<String> executeWithReservation(Request request, Supplier<ApiResult> work) {
        UUID key = parseKey(request.rawKey());
        String hash = hash(request);
        Long userId = request.caller() == null ? null : request.caller().userId();

        Object reservation = readCommitted.execute(status -> {
            Long keyId = insertKey(userId, key, request, hash);
            return keyId != null ? keyId : replayOrReject(userId, key, hash);
        });
        if (reservation instanceof Stored stored) {
            return toResponse(stored);
        }
        long keyId = (Long) reservation;

        ApiResult outcome;
        try {
            outcome = work.get();
        } catch (ApiException e) {
            if (e.status().is5xxServerError()) {
                releaseReservation(keyId);
                throw e;
            }
            outcome = ApiResult.error(e);
        } catch (RuntimeException e) {
            releaseReservation(keyId);
            throw e;
        }
        String body = toJson(outcome.body());
        complete(keyId, outcome.status(), body);
        return toResponse(new Stored(outcome.status(), body, false));
    }

    /** Deletes completed keys older than the retention window. Returns rows deleted. */
    public int purgeOlderThanHours(int hours) {
        return jdbc.update(
                "DELETE FROM idempotency_keys WHERE status = 'COMPLETED' AND created_at < now() - make_interval(hours => ?)",
                hours);
    }

    // -----------------------------------------------------------------------------------------

    /** Server-side failure: forget the reservation so the client's retry runs the work again. */
    private void releaseReservation(long keyId) {
        jdbc.update("DELETE FROM idempotency_keys WHERE id = ? AND status = 'IN_PROGRESS'", keyId);
    }

    private Long insertKey(Long userId, UUID key, Request request, String hash) {
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO idempotency_keys (user_id, idem_key, request_method, request_path, request_hash)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT uq_idempotency_keys_user_key DO NOTHING
                RETURNING id
                """, Long.class, userId, key, request.method(), request.path(), hash);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Stored replayOrReject(Long userId, UUID key, String hash) {
        return jdbc.queryForObject("""
                SELECT request_hash, status, response_code, response_body
                  FROM idempotency_keys
                 WHERE user_id IS NOT DISTINCT FROM ? AND idem_key = ?
                """, (rs, n) -> {
            if (!hash.equals(rs.getString("request_hash"))) {
                throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used with a different request");
            }
            if (!"COMPLETED".equals(rs.getString("status"))) {
                throw inProgress();
            }
            return new Stored(rs.getInt("response_code"), rs.getString("response_body"), true);
        }, userId, key);
    }

    private void complete(long keyId, int status, String body) {
        jdbc.update("""
                UPDATE idempotency_keys
                   SET status = 'COMPLETED', response_code = ?, response_body = ?, completed_at = now()
                 WHERE id = ?
                """, status, body, keyId);
    }

    /** Transaction-local audit context read by the transfers audit trigger. */
    private void setActor(Caller caller) {
        jdbc.queryForList("SELECT set_config('wallet.actor', ?, true), set_config('wallet.actor_user_id', ?, true)",
                caller == null ? "anonymous" : caller.actor(),
                caller == null ? "" : Long.toString(caller.userId()));
    }

    private static ApiException inProgress() {
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_PROGRESS",
                "A request with this Idempotency-Key is still being processed; retry shortly");
    }

    private static String lockName(Long userId, UUID key) {
        return "idem:" + (userId == null ? "anon" : userId) + ":" + key;
    }

    private static UUID parseKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Every POST needs an " + HEADER + " header (a UUID)");
        }
        try {
            UUID key = UUID.fromString(raw.trim());
            if (!key.toString().equalsIgnoreCase(raw.trim())) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", HEADER + " must be a UUID");
        }
    }

    /** SHA-256 over method, path and the body with object keys sorted, so key order and spacing don't matter. */
    private String hash(Request request) {
        try {
            Object canonical = request.body() == null ? null : canonicalMapper.convertValue(request.body(), Object.class);
            String material = request.method() + "\n" + request.path() + "\n" + canonicalMapper.writeValueAsString(canonical);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseEntity<String> toResponse(Stored stored) {
        return ResponseEntity.status(stored.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header(REPLAY_HEADER, Boolean.toString(stored.replayed()))
                .body(stored.body());
    }
}
