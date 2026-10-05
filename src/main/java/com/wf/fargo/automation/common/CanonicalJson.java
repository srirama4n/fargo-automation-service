package com.wf.fargo.automation.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** RFC 8785 canonical JSON for the subset we hash: objects, arrays, strings, numbers, booleans, null. */
public final class CanonicalJson {

    private static final ObjectMapper STRINGS = new ObjectMapper();

    private CanonicalJson() {}

    public static String of(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
            return;
        }
        if (node.getNodeType() == JsonNodeType.OBJECT || node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                String name = names.get(i);
                out.append(quote(name)).append(':');
                write(node.get(name), out);
            }
            out.append('}');
            return;
        }
        if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
            return;
        }
        if (node.isTextual()) {
            out.append(quote(node.asText()));
            return;
        }
        if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
            return;
        }
        if (node.isNumber()) {
            out.append(number(node));
            return;
        }
        throw new IllegalArgumentException("Unsupported JSON value");
    }

    private static String number(JsonNode node) {
        BigDecimal decimal = node.decimalValue().stripTrailingZeros();
        if (decimal.scale() < 0) {
            decimal = decimal.setScale(0);
        }
        return decimal.toPlainString();
    }

    private static String quote(String value) {
        try {
            return STRINGS.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not encode a JSON string", ex);
        }
    }
}
