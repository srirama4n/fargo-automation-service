package com.wf.fargo.automation.common;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RawAccountNumbers {

    private static final Logger log = LoggerFactory.getLogger(RawAccountNumbers.class);
    private static final Pattern TOKEN = Pattern.compile("^(acct|card)_tok_[A-Za-z0-9]+$");
    private static final Pattern RAW_NUMBER = Pattern.compile("\\d{13,19}");

    private RawAccountNumbers() {}

    public static void reject(Object value) {
        if (value instanceof String text) {
            if (!TOKEN.matcher(text).matches() && RAW_NUMBER.matcher(text).find()) {
                rejectRaw();
            }
            return;
        }
        if (value instanceof Number number) {
            if (RAW_NUMBER.matcher(digits(number)).matches()) {
                rejectRaw();
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object nested : map.values()) {
                reject(nested);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object nested : iterable) {
                reject(nested);
            }
        }
    }

    private static String digits(Number number) {
        String text = number instanceof BigDecimal decimal ? decimal.toPlainString() : number.toString();
        int sign = text.startsWith("-") ? 1 : 0;
        int dot = text.indexOf('.');
        return dot < 0 ? text.substring(sign) : text.substring(sign, dot);
    }

    private static void rejectRaw() {
        log.warn("security=true raw_account_number");
        throw new ApiException(400, "raw_account_number", "Account and card numbers aren't accepted.");
    }
}
