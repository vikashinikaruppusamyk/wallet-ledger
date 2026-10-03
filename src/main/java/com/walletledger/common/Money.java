package com.walletledger.common;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/** Display helpers. Money is stored and computed as long paise; only formatting touches decimals. */
public final class Money {

    /** Upper bound on a single amount: ₹1,000 crore. Keeps every sum far away from long overflow. */
    public static final long MAX_AMOUNT_PAISE = 10_000_000_000_000L;

    private static final Locale INDIA = Locale.of("en", "IN");

    private Money() {
    }

    public static String format(long paise) {
        NumberFormat format = NumberFormat.getCurrencyInstance(INDIA);
        return format.format(BigDecimal.valueOf(paise, 2));
    }
}
