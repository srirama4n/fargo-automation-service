package com.wf.fargo.automation.common;

import java.util.regex.Pattern;

public final class LogSanitizer {

    private static final Pattern TOKEN = Pattern.compile("(acct|card)_tok_[A-Za-z0-9]+");
    private static final Pattern RAW_NUMBER = Pattern.compile("\\d{13,19}");

    private LogSanitizer() {}

    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = TOKEN.matcher(text).replaceAll(match -> {
            String token = match.group();
            return "***" + token.substring(token.length() - 4);
        });
        return RAW_NUMBER.matcher(masked).replaceAll("***");
    }
}
