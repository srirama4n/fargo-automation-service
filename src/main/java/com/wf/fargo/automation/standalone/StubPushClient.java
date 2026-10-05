package com.wf.fargo.automation.standalone;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"standalone", "test"})
public class StubPushClient {

    private static final Logger log = LoggerFactory.getLogger(StubPushClient.class);

    private final List<Map<String, Object>> sent = new CopyOnWriteArrayList<>();

    public void send(Map<String, Object> payload) {
        Map<String, Object> copy = payload == null ? Map.of() : Map.copyOf(payload);
        sent.add(copy);
        log.info("push category={} collapseKey={}", copy.get("category"), copy.get("collapseKey"));
    }

    public List<Map<String, Object>> sent() {
        return List.copyOf(sent);
    }
}
