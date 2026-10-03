package com.walletledger.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletledger.common.ApiResult;
import com.walletledger.common.Caller;
import com.walletledger.common.CallerResolver;
import com.walletledger.common.RequestReader;
import com.walletledger.explain.FlagExplanationService;
import com.walletledger.idempotency.IdempotencyService;
import com.walletledger.recon.ReconciliationService;
import com.walletledger.transfer.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
public class AdminController {

    public record DecisionRequest(@NotBlank @Pattern(regexp = "RELEASE|REJECT") String decision,
                                  @NotBlank @Size(max = 500) String reason) {
    }

    public record QueueItem(long transferId, String type, String status, long amount, long fromAccountId,
                            long toAccountId, String note, OffsetDateTime createdAt, JsonNode flags) {
    }

    private final CallerResolver callers;
    private final RequestReader reader;
    private final IdempotencyService idempotency;
    private final TransferService transfers;
    private final ReconciliationService reconciliation;
    private final FlagExplanationService explanations;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AdminController(CallerResolver callers, RequestReader reader, IdempotencyService idempotency,
                           TransferService transfers, ReconciliationService reconciliation,
                           FlagExplanationService explanations, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.callers = callers;
        this.reader = reader;
        this.idempotency = idempotency;
        this.transfers = transfers;
        this.reconciliation = reconciliation;
        this.explanations = explanations;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ---- Reconciliation ---------------------------------------------------------------------

    /** Starts a run in the background and answers 202 with the run id; 409 if one is already running. */
    @PostMapping("/admin/reconciliation/run")
    public ResponseEntity<String> runReconciliation(@RequestHeader(value = CallerResolver.HEADER, required = false) String userHeader,
                                                    @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                                    @RequestBody(required = false) JsonNode body, HttpServletRequest http) {
        Caller admin = callers.requireAdmin(userHeader);
        return idempotency.executeWithReservation(request(admin, key, http, body),
                () -> ApiResult.of(HttpStatus.ACCEPTED.value(), reconciliation.startManualRun()));
    }

    @GetMapping("/admin/reconciliation/runs/{runId}")
    public ReconciliationService.RunReport run(@RequestHeader(value = CallerResolver.HEADER, required = false) String userHeader,
                                               @PathVariable long runId) {
        callers.requireAdmin(userHeader);
        return reconciliation.report(runId);
    }

    // ---- Risk ---------------------------------------------------------------------------------

    /** Held transfers first (they wait on a human), then flagged-but-allowed ones, newest first. */
    @GetMapping("/admin/risk/queue")
    public List<QueueItem> riskQueue(@RequestHeader(value = CallerResolver.HEADER, required = false) String userHeader,
                                     @RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(defaultValue = "0") int offset) {
        callers.requireAdmin(userHeader);
        return jdbc.query("""
                SELECT t.id, t.type, t.status, t.amount, t.from_account_id, t.to_account_id, t.note, t.created_at,
                       json_agg(json_build_object(
                           'flag_id', f.id, 'rule', r.code, 'action', f.action, 'details', f.details,
                           'llm_label', f.llm_label, 'llm_explanation', f.llm_explanation) ORDER BY f.id)::text AS flags
                  FROM transfers t
                  JOIN risk_flags f ON f.transfer_id = t.id
                  JOIN risk_rules r ON r.id = f.rule_id
                 GROUP BY t.id
                 ORDER BY (t.status = 'HELD') DESC, t.created_at DESC, t.id DESC
                 LIMIT ? OFFSET ?
                """, (rs, n) -> new QueueItem(rs.getLong("id"), rs.getString("type"), rs.getString("status"),
                rs.getLong("amount"), rs.getLong("from_account_id"), rs.getLong("to_account_id"), rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class), readJson(rs.getString("flags"))),
                Math.min(Math.max(limit, 1), 200), Math.max(offset, 0));
    }

    /** RELEASE posts a held transfer (balance re-checked under lock); REJECT closes it. */
    @PostMapping("/admin/risk/{transferId}/decision")
    public ResponseEntity<String> decide(@RequestHeader(value = CallerResolver.HEADER, required = false) String userHeader,
                                         @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                         @PathVariable long transferId,
                                         @RequestBody(required = false) JsonNode body, HttpServletRequest http) {
        Caller admin = callers.requireAdmin(userHeader);
        DecisionRequest r = reader.read(body, DecisionRequest.class);
        return idempotency.executeInTransaction(request(admin, key, http, body),
                () -> transfers.decide(admin, transferId, r.decision(), r.reason()));
    }

    /** Runs the nightly "explain the flags" step on demand. */
    @PostMapping("/admin/risk/explain")
    public ResponseEntity<String> explain(@RequestHeader(value = CallerResolver.HEADER, required = false) String userHeader,
                                          @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                          @RequestBody(required = false) JsonNode body, HttpServletRequest http) {
        Caller admin = callers.requireAdmin(userHeader);
        return idempotency.executeWithReservation(request(admin, key, http, body),
                () -> ApiResult.of(HttpStatus.OK.value(), explanations.explainPending(500)));
    }

    private static IdempotencyService.Request request(Caller caller, String key, HttpServletRequest http, JsonNode body) {
        return new IdempotencyService.Request(caller, key, http.getMethod(), http.getRequestURI(), body);
    }

    private JsonNode readJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
