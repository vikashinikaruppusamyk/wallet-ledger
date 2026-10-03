package com.walletledger.common;

/** A response the idempotency layer can store and replay: status code plus body object. */
public record ApiResult(int status, Object body) {

    public static ApiResult of(int status, Object body) {
        return new ApiResult(status, body);
    }

    public static ApiResult error(ApiException e) {
        return new ApiResult(e.status().value(), ErrorBody.of(e.code(), e.getMessage()));
    }
}
