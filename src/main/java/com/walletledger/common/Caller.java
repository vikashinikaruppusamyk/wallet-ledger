package com.walletledger.common;

/** The authenticated user making a request. */
public record Caller(long userId, boolean admin) {

    /** Actor string recorded in the transfer audit log. */
    public String actor() {
        return (admin ? "admin:" : "user:") + userId;
    }

    public void requireSelfOrAdmin(long userId) {
        if (!admin && this.userId != userId) {
            throw ApiException.forbidden("You can only access your own data");
        }
    }

    public void requireAdmin() {
        if (!admin) {
            throw ApiException.forbidden("Admin role required");
        }
    }
}
