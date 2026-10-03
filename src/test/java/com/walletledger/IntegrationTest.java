package com.walletledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletledger.llm.LlmClient;
import com.walletledger.llm.MockLlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class: the real application on a random port, against a real Postgres, driven over HTTP.
 * All test classes share one Spring context (same annotations), so the schema is built once per run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestDatabaseConfig.class)
public abstract class IntegrationTest {

    private static final AtomicLong UNIQUE = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    protected static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(java.util.concurrent.Executors.newCachedThreadPool())
            .build();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    protected PlatformTransactionManager txManager;

    @Autowired
    protected LlmClient llm;

    protected long adminId;

    @BeforeEach
    void resetCommonState() {
        mock().reset();
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE role = 'ADMIN' ORDER BY id LIMIT 1", Long.class);
    }

    protected MockLlmClient mock() {
        return (MockLlmClient) llm;
    }

    // ---- HTTP -------------------------------------------------------------------------------

    public record Resp(int status, JsonNode body, String raw, HttpResponse<String> http) {
        public String header(String name) {
            return http.headers().firstValue(name).orElse(null);
        }
    }

    protected Resp post(String path, Long userId, String idempotencyKey, Object body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : json(body)));
        if (userId != null) {
            b.header("X-User-Id", userId.toString());
        }
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        return send(b.build());
    }

    protected Resp get(String path, Long userId) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60)).GET();
        if (userId != null) {
            b.header("X-User-Id", userId.toString());
        }
        return send(b.build());
    }

    private Resp send(HttpRequest request) {
        try {
            HttpResponse<String> r = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = r.body() == null || r.body().isBlank() ? null : mapper.readTree(r.body());
            return new Resp(r.statusCode(), body, r.body(), r);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected static String key() {
        return UUID.randomUUID().toString();
    }

    // ---- Fixtures through the public API ------------------------------------------------------

    protected long createUser(String name) {
        long n = UNIQUE.incrementAndGet();
        Resp r = post("/users", null, key(), java.util.Map.of(
                "name", name, "email", "user" + n + "@test.example", "phone", "+91" + String.format("%010d", n)));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.body().get("user_id").asLong();
    }

    protected long verifiedUser(String name) {
        long id = createUser(name);
        Resp r = post("/admin/users/" + id + "/kyc", adminId, key(), java.util.Map.of("kyc_status", "VERIFIED"));
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        return id;
    }

    protected long openWallet(long userId, String name) {
        Resp r = post("/users/" + userId + "/wallets", userId, key(), java.util.Map.of("name", name));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.body().get("wallet_id").asLong();
    }

    protected Resp topUp(long userId, long walletId, long amount) {
        Resp r = post("/transfers/topup", userId, key(), java.util.Map.of("wallet_id", walletId, "amount", amount));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r;
    }

    protected Resp transfer(long userId, long from, long to, long amount, String note) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("from_wallet_id", from);
        body.put("to_wallet_id", to);
        body.put("amount", amount);
        if (note != null) {
            body.put("note", note);
        }
        return post("/transfers", userId, key(), body);
    }

    protected long balance(long walletId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, walletId);
    }

    protected long ledgerBalance(long accountId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'C' THEN amount ELSE -amount END), 0)
                  FROM ledger_entries WHERE account_id = ?
                """, Long.class, accountId);
    }

    // ---- Fixtures written straight to the database (back-dated history) ---------------------

    /** A POSTED peer/top-up transfer with an explicit timestamp, written the same way the API writes it. */
    protected long postedAt(String type, long from, long to, long amount, String note, OffsetDateTime at) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return tx.execute(s -> {
            jdbc.queryForList("SELECT set_config('wallet.actor', 'system:test-fixture', true)");
            long id = jdbc.queryForObject("""
                    INSERT INTO transfers (type, status, amount, from_account_id, to_account_id, note, created_at, updated_at)
                    VALUES (?, 'POSTED', ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, type, amount, from, to, note, at, at);
            jdbc.update("""
                    INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, created_at)
                    VALUES (?, ?, 'D', ?, ?), (?, ?, 'C', ?, ?)
                    """, id, from, amount, at, id, to, amount, at);
            jdbc.update("UPDATE accounts SET balance = balance - ? WHERE id = ?", amount, from);
            jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", amount, to);
            return id;
        });
    }

    protected long cashInAccount() {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE system_code = 'CASH_IN'", Long.class);
    }

    protected void setRuleEnabled(String code, boolean enabled) {
        jdbc.update("UPDATE risk_rules SET enabled = ? WHERE code = ?", enabled, code);
    }
}
