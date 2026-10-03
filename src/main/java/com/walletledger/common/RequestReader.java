package com.walletledger.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a raw JSON body into a validated request record. Controllers keep the
 * raw JsonNode too, because the idempotency layer hashes the body as sent.
 */
@Component
public class RequestReader {

    private final ObjectMapper mapper;
    private final Validator validator;

    public RequestReader(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    public <T> T read(JsonNode body, Class<T> type) {
        if (body == null || !body.isObject()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "Request body must be a JSON object");
        }
        T value;
        try {
            value = mapper.treeToValue(body, type);
        } catch (JsonProcessingException e) {
            // e.g. "amount": 10.5 or "amount": "10" - money must be an integer number of paise.
            throw ApiException.badRequest("VALIDATION_FAILED", "Invalid field: " + e.getOriginalMessage());
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; "));
            throw ApiException.badRequest("VALIDATION_FAILED", message);
        }
        return value;
    }
}
