package com.walletledger.common;

import org.slf4j.MDC;

/** The one error shape used everywhere: {"code": ..., "message": ..., "request_id": ...}. */
public record ErrorBody(String code, String message, String requestId) {

    public static ErrorBody of(String code, String message) {
        return new ErrorBody(code, message, MDC.get(RequestIdFilter.MDC_KEY));
    }
}
