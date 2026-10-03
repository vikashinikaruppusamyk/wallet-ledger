package com.walletledger.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletledger.common.Caller;
import com.walletledger.common.CallerResolver;
import com.walletledger.common.Money;
import com.walletledger.common.RequestReader;
import com.walletledger.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

    public record TopUpRequest(@NotNull @Positive Long walletId,
                               @NotNull @Positive @Max(Money.MAX_AMOUNT_PAISE) Long amount,
                               @Size(max = 280) String note) {
    }

    public record PeerTransferRequest(@NotNull @Positive Long fromWalletId,
                                      @NotNull @Positive Long toWalletId,
                                      @NotNull @Positive @Max(Money.MAX_AMOUNT_PAISE) Long amount,
                                      @Size(max = 280) String note) {
    }

    public record WithdrawRequest(@NotNull @Positive Long walletId,
                                  @NotNull @Positive @Max(Money.MAX_AMOUNT_PAISE) Long amount) {
    }

    public record ReverseRequest(@NotNull @Size(min = 1, max = 500) String reason) {
    }

    private final CallerResolver callers;
    private final RequestReader reader;
    private final IdempotencyService idempotency;
    private final TransferService transfers;
    private final TransferQueries queries;

    public TransferController(CallerResolver callers, RequestReader reader, IdempotencyService idempotency,
                              TransferService transfers, TransferQueries queries) {
        this.callers = callers;
        this.reader = reader;
        this.idempotency = idempotency;
        this.transfers = transfers;
        this.queries = queries;
    }

    @PostMapping("/transfers/topup")
    public ResponseEntity<String> topUp(@RequestHeader(value = CallerResolver.HEADER, required = false) String userHeader,
                                        @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                        @RequestBody(required = false) JsonNode body, HttpServletRequest http) {
        Caller caller = callers.require(userHeader);
        TopUpRequest r = reader.read(body, TopUpRequest.class);
        return idempotency.executeInTransaction(request(caller, key, http, body),
                () -> transfers.topUp(caller, r.walletId(), r.amount(), r.note()));
    }

    @PostMapping("/transfers")
    public ResponseEntity<String> transfer(@RequestHeader(value = CallerResolver.HEADER, required = false) String userHeader,
                                           @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                           @RequestBody(required = false) JsonNode body, HttpServletRequest http) {
        Caller caller = callers.require(userHeader);
        PeerTransferRequest r = reader.read(body, PeerTransferRequest.class);
        return idempotency.executeInTransaction(request(caller, key, http, body),
                () -> transfers.peerTransfer(caller, r.fromWalletId(), r.toWalletId(), r.amount(), r.note()));
    }

    @PostMapping("/transfers/withdraw")
    public ResponseEntity<String> withdraw(@RequestHeader(value = CallerResolver.HEADER, required = false) String userHeader,
                                           @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                           @RequestBody(required = false) JsonNode body, HttpServletRequest http) {
        Caller caller = callers.require(userHeader);
        WithdrawRequest r = reader.read(body, WithdrawRequest.class);
        return idempotency.executeInTransaction(request(caller, key, http, body),
                () -> transfers.withdraw(caller, r.walletId(), r.amount()));
    }

    @PostMapping("/transfers/{id}/reverse")
    public ResponseEntity<String> reverse(@RequestHeader(value = CallerResolver.HEADER, required = false) String userHeader,
                                          @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                          @PathVariable long id,
                                          @RequestBody(required = false) JsonNode body, HttpServletRequest http) {
        Caller admin = callers.requireAdmin(userHeader);
        ReverseRequest r = reader.read(body, ReverseRequest.class);
        return idempotency.executeInTransaction(request(admin, key, http, body),
                () -> transfers.reverse(admin, id, r.reason()));
    }

    @GetMapping("/transfers/{id}")
    public TransferQueries.TransferView get(@RequestHeader(value = CallerResolver.HEADER, required = false) String userHeader,
                                            @PathVariable long id) {
        Caller caller = callers.require(userHeader);
        TransferQueries.TransferView view = queries.find(id);
        queries.requireVisible(caller, view);
        return view;
    }

    static IdempotencyService.Request request(Caller caller, String key, HttpServletRequest http, JsonNode body) {
        return new IdempotencyService.Request(caller, key, http.getMethod(), http.getRequestURI(), body);
    }
}
