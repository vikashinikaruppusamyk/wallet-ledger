package com.walletledger.account;

import com.walletledger.common.ApiException;
import com.walletledger.common.ApiResult;
import com.walletledger.common.Caller;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class AccountService {

    public record UserView(long userId, String name, String email, String phone, String kycStatus, OffsetDateTime createdAt) {
    }

    public record WalletView(long walletId, long userId, String name, String currency, long balance, OffsetDateTime createdAt) {
    }

    private final JdbcTemplate jdbc;

    public AccountService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Runs inside the idempotency transaction. */
    public ApiResult createUser(String name, String email, String phone) {
        long id;
        try {
            id = jdbc.queryForObject(
                    "INSERT INTO users (name, email, phone) VALUES (?, ?, ?) RETURNING id",
                    Long.class, name.trim(), email.trim(), phone.trim());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("USER_EXISTS", "A user with this email or phone already exists");
        }
        return ApiResult.of(HttpStatus.CREATED.value(), user(id));
    }

    /** Runs inside the idempotency transaction. */
    public ApiResult openWallet(Caller caller, long userId, String name) {
        caller.requireSelfOrAdmin(userId);
        if (jdbc.queryForList("SELECT 1 FROM users WHERE id = ?", Integer.class, userId).isEmpty()) {
            throw ApiException.notFound("USER_NOT_FOUND", "User " + userId + " does not exist");
        }
        long id;
        try {
            id = jdbc.queryForObject(
                    "INSERT INTO accounts (user_id, kind, name) VALUES (?, 'USER_WALLET', ?) RETURNING id",
                    Long.class, userId, name.trim());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("WALLET_EXISTS", "User " + userId + " already has a wallet named '" + name.trim() + "'");
        }
        return ApiResult.of(HttpStatus.CREATED.value(), wallet(id));
    }

    /** Runs inside the idempotency transaction. Admin-only KYC update (not part of the minimum API). */
    public ApiResult setKyc(Caller admin, long userId, String kycStatus) {
        admin.requireAdmin();
        int updated = jdbc.update("UPDATE users SET kyc_status = ? WHERE id = ?", kycStatus, userId);
        if (updated == 0) {
            throw ApiException.notFound("USER_NOT_FOUND", "User " + userId + " does not exist");
        }
        return ApiResult.of(HttpStatus.OK.value(), user(userId));
    }

    public WalletView balance(Caller caller, long walletId) {
        WalletView wallet = wallet(walletId);
        if (!caller.admin() && wallet.userId() != caller.userId()) {
            // Same answer as "does not exist", so wallet ids can't be probed.
            throw ApiException.notFound("WALLET_NOT_FOUND", "Wallet " + walletId + " does not exist");
        }
        return wallet;
    }

    public UserView user(long id) {
        return jdbc.queryForObject("SELECT id, name, email, phone, kyc_status, created_at FROM users WHERE id = ?",
                (rs, n) -> new UserView(rs.getLong("id"), rs.getString("name"), rs.getString("email"),
                        rs.getString("phone"), rs.getString("kyc_status"), rs.getObject("created_at", OffsetDateTime.class)), id);
    }

    public WalletView wallet(long id) {
        List<WalletView> rows = jdbc.query("""
                SELECT id, user_id, name, currency, balance, created_at
                  FROM accounts WHERE id = ? AND kind = 'USER_WALLET'
                """, (rs, n) -> new WalletView(rs.getLong("id"), rs.getLong("user_id"), rs.getString("name"),
                rs.getString("currency"), rs.getLong("balance"), rs.getObject("created_at", OffsetDateTime.class)), id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("WALLET_NOT_FOUND", "Wallet " + id + " does not exist");
        }
        return rows.get(0);
    }
}
