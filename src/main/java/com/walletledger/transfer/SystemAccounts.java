package com.walletledger.transfer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Ids of the system accounts (cash-in, cash-out, fee revenue). They never change, so they are read once. */
@Component
public class SystemAccounts {

    private final JdbcTemplate jdbc;
    private volatile Map<String, Long> ids;

    public SystemAccounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long cashIn() {
        return id("CASH_IN");
    }

    public long cashOut() {
        return id("CASH_OUT");
    }

    public long feeRevenue() {
        return id("FEE_REVENUE");
    }

    public boolean isSystem(long accountId) {
        return load().containsValue(accountId);
    }

    private long id(String code) {
        return load().get(code);
    }

    private Map<String, Long> load() {
        Map<String, Long> loaded = ids;
        if (loaded == null) {
            Map<String, Long> fresh = new HashMap<>();
            jdbc.query("SELECT system_code, id FROM accounts WHERE kind = 'SYSTEM'",
                    rs -> {
                        fresh.put(rs.getString("system_code"), rs.getLong("id"));
                    });
            ids = loaded = Map.copyOf(fresh);
        }
        return loaded;
    }
}
