package com.walletledger.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletledger.common.Caller;
import com.walletledger.common.CallerResolver;
import com.walletledger.common.RequestReader;
import com.walletledger.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

    public record CreateUserRequest(@NotBlank @Size(max = 200) String name,
                                    @NotBlank @Email @Size(max = 254) String email,
                                    @NotBlank @Pattern(regexp = "^\\+?[0-9]{8,15}$") String phone) {
    }

    public record OpenWalletRequest(@NotBlank @Size(max = 50) String name) {
    }

    public record KycRequest(@NotBlank @Pattern(regexp = "PENDING|VERIFIED|REJECTED") String kycStatus) {
    }

    private final CallerResolver callers;
    private final RequestReader reader;
    private final IdempotencyService idempotency;
    private final AccountService accounts;
    private final StatementService statements;

    public AccountController(CallerResolver callers, RequestReader reader, IdempotencyService idempotency,
                             AccountService accounts, StatementService statements) {
        this.callers = callers;
        this.reader = reader;
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.statements = statements;
    }

    /** Sign-up: no caller yet, so the idempotency key is scoped to "anonymous". */
    @PostMapping("/users")
    public ResponseEntity<String> createUser(@RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                             @RequestBody(required = false) JsonNode body, HttpServletRequest http) {
        CreateUserRequest r = reader.read(body, CreateUserRequest.class);
        return idempotency.executeInTransaction(
                new IdempotencyService.Request(null, key, http.getMethod(), http.getRequestURI(), body),
                () -> accounts.createUser(r.name(), r.email(), r.phone()));
    }

    @PostMapping("/users/{userId}/wallets")
    public ResponseEntity<String> openWallet(@RequestHeader(value = CallerResolver.HEADER, required = false) String userHeader,
                                             @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                             @PathVariable long userId,
                                             @RequestBody(required = false) JsonNode body, HttpServletRequest http) {
        Caller caller = callers.require(userHeader);
        OpenWalletRequest r = reader.read(body, OpenWalletRequest.class);
        return idempotency.executeInTransaction(
                new IdempotencyService.Request(caller, key, http.getMethod(), http.getRequestURI(), body),
                () -> accounts.openWallet(caller, userId, r.name()));
    }

    @PostMapping("/admin/users/{userId}/kyc")
    public ResponseEntity<String> setKyc(@RequestHeader(value = CallerResolver.HEADER, required = false) String userHeader,
                                         @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                         @PathVariable long userId,
                                         @RequestBody(required = false) JsonNode body, HttpServletRequest http) {
        Caller admin = callers.requireAdmin(userHeader);
        KycRequest r = reader.read(body, KycRequest.class);
        return idempotency.executeInTransaction(
                new IdempotencyService.Request(admin, key, http.getMethod(), http.getRequestURI(), body),
                () -> accounts.setKyc(admin, userId, r.kycStatus()));
    }

    @GetMapping("/wallets/{walletId}/balance")
    public AccountService.WalletView balance(@RequestHeader(value = CallerResolver.HEADER, required = false) String userHeader,
                                             @PathVariable long walletId) {
        return accounts.balance(callers.require(userHeader), walletId);
    }

    @GetMapping("/wallets/{walletId}/statement")
    public StatementService.StatementPage statement(@RequestHeader(value = CallerResolver.HEADER, required = false) String userHeader,
                                                    @PathVariable long walletId,
                                                    @RequestParam(defaultValue = "20") int limit,
                                                    @RequestParam(required = false) Integer offset,
                                                    @RequestParam(required = false) String cursor) {
        accounts.balance(callers.require(userHeader), walletId); // ownership check
        return statements.page(walletId, limit, offset, cursor);
    }
}
