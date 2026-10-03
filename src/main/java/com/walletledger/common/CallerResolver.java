package com.walletledger.common;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Identifies the caller from the X-User-Id header.
 * Authentication is out of scope for this project: in production this would
 * come from a verified token, not a header the client controls.
 */
@Component
public class CallerResolver {

    public static final String HEADER = "X-User-Id";

    private final JdbcTemplate jdbc;

    public CallerResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Caller require(String header) {
        if (header == null || header.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Missing " + HEADER + " header");
        }
        long userId;
        try {
            userId = Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", HEADER + " must be a numeric user id");
        }
        List<String> roles = jdbc.queryForList("SELECT role FROM users WHERE id = ?", String.class, userId);
        if (roles.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Unknown user " + userId);
        }
        return new Caller(userId, "ADMIN".equals(roles.get(0)));
    }

    public Caller requireAdmin(String header) {
        Caller caller = require(header);
        caller.requireAdmin();
        return caller;
    }
}
