package com.walletledger.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletledger.common.Caller;
import com.walletledger.common.CallerResolver;
import com.walletledger.common.RequestReader;
import com.walletledger.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatementQuestionController {

    public record AskRequest(@NotBlank @Size(max = 500) String question) {
    }

    private final CallerResolver callers;
    private final RequestReader reader;
    private final IdempotencyService idempotency;
    private final StatementQuestionService questions;

    public StatementQuestionController(CallerResolver callers, RequestReader reader, IdempotencyService idempotency,
                                       StatementQuestionService questions) {
        this.callers = callers;
        this.reader = reader;
        this.idempotency = idempotency;
        this.questions = questions;
    }

    /** Reservation mode: the LLM call happens outside any database transaction. */
    @PostMapping("/users/{userId}/statement/ask")
    public ResponseEntity<String> ask(@RequestHeader(value = CallerResolver.HEADER, required = false) String userHeader,
                                      @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                      @PathVariable long userId,
                                      @RequestBody(required = false) JsonNode body, HttpServletRequest http) {
        Caller caller = callers.require(userHeader);
        AskRequest r = reader.read(body, AskRequest.class);
        return idempotency.executeWithReservation(
                new IdempotencyService.Request(caller, key, http.getMethod(), http.getRequestURI(), body),
                () -> questions.ask(caller, userId, r.question()));
    }
}
