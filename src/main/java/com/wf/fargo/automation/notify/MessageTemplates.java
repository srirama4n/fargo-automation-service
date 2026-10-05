package com.wf.fargo.automation.notify;

import com.wf.fargo.automation.domain.ActivityType;
import java.util.Map;

public final class MessageTemplates {

    private MessageTemplates() {}

    public static String render(ActivityType type, String agentName) {
        return render(type, agentName, Map.of());
    }

    public static String render(ActivityType type, String agentName, Map<String, String> values) {
        String name = agentName == null || agentName.isBlank() ? "This agent" : agentName;
        Map<String, String> fields = values == null ? Map.of() : values;
        return switch (type) {
            case CREATED -> name + " is on.";
            case PAUSED -> name + " is paused.";
            case RESUMED -> name + " is on again.";
            case DELETED -> name + " was deleted.";
            case FIRED -> name + " started a check.";
            case SKIPPED -> name + " did not run.";
            case APPROVAL_REQUESTED -> approvalRequested(name, fields);
            case NOTIFIED -> text(fields.get("effect"), name + " has an update.");
            case APPROVED -> name + " was approved.";
            case DENIED -> name + " was declined.";
            case EXPIRED -> "This request expired, nothing was changed.";
            case COMPLETED -> name + " is done.";
            case FAILED -> "execute".equals(fields.get("phase"))
                    ? "We couldn't complete this."
                    : "We couldn't complete this. Nothing was changed.";
            default -> throw new IllegalArgumentException("No message template for " + type);
        };
    }

    private static String approvalRequested(String name, Map<String, String> fields) {
        String merchant = fields.get("merchantName");
        String oldAmount = fields.get("oldAmount");
        String newAmount = fields.get("newAmount");
        String card = fields.get("cardDisplay");
        if (merchant != null && oldAmount != null && newAmount != null && card != null) {
            return merchant + " went from $" + oldAmount + " to $" + newAmount
                    + ". Block future " + merchant + " charges on " + card + "?";
        }
        return "Approve " + name + "?";
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
