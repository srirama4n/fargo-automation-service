package com.wf.fargo.automation.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class ParamTokens {

    static final Pattern TOKEN = Pattern.compile("^(acct|card)_tok_[A-Za-z0-9]+$");

    private ParamTokens() {}

    static List<String> collect(Object value) {
        List<String> tokens = new ArrayList<>();
        walk(value, tokens);
        return List.copyOf(tokens);
    }

    private static void walk(Object value, List<String> tokens) {
        if (value instanceof String text) {
            if (TOKEN.matcher(text).matches()) {
                tokens.add(text);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object nested : map.values()) {
                walk(nested, tokens);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object nested : iterable) {
                walk(nested, tokens);
            }
        }
    }
}
