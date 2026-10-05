package com.wf.fargo.automation.notify;

import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.outbox.OutboxWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class Notifier {

    private final OutboxWriter outbox;

    public Notifier(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void push(
            String customerId, String title, String body, String deepLink, String category, String collapseKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", customerId);
        payload.put("title", title);
        payload.put("body", body);
        if (deepLink != null) {
            payload.put("deepLink", deepLink);
        }
        payload.put("category", category);
        payload.put("collapseKey", collapseKey);
        outbox.write(OutboxDestination.PUSH, customerId, payload);
    }
}
