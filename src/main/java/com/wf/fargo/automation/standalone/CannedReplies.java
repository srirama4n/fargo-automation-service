package com.wf.fargo.automation.standalone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
@org.springframework.context.annotation.Profile({"standalone", "test"})
public class CannedReplies {

    private static final Logger log = LoggerFactory.getLogger(CannedReplies.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AppProperties properties;
    private final Map<String, Map<String, Object>> byIntent;
    private final ConcurrentLinkedQueue<Map<String, Object>> overrides = new ConcurrentLinkedQueue<>();

    public CannedReplies(ObjectMapper objectMapper, Clock clock, AppProperties properties) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
        this.byIntent = load();
    }

    public void queueNext(Map<String, Object> override) {
        overrides.add(override == null ? Map.of() : new LinkedHashMap<>(override));
    }

    public Map<String, Object> build(Map<String, Object> task) {
        Map<String, Object> source = task == null ? Map.of() : task;
        String intent = text(source.get("intent"));
        Map<String, Object> reply = new LinkedHashMap<>();
        Map<String, Object> canned = intent == null ? null : byIntent.get(intent);
        if (canned != null) {
            reply.putAll(canned);
        } else {
            log.warn("canned_reply_missing intent={}", intent);
            reply.put("status", "error");
            reply.put("quote", null);
            reply.put("outcome", null);
            reply.put("error", Map.of("code", "unknown_intent", "message", "No canned reply for this intent.", "retryable", false));
        }
        Map<String, Object> override = overrides.poll();
        if (override != null) {
            reply.putAll(override);
        }
        reply.putIfAbsent("schemaVersion", 1);
        reply.putIfAbsent("messageId", Ulids.next(clock.millis()));
        reply.putIfAbsent("occurredAt", clock.instant().toString());
        copy(reply, "taskId", source.get("taskId"));
        copy(reply, "runId", source.get("runId"));
        copy(reply, "customerId", source.get("customerId"));
        copy(reply, "phase", source.get("phase"));
        copy(reply, "traceId", source.get("traceId"));
        reply.putIfAbsent("quote", null);
        reply.putIfAbsent("outcome", null);
        reply.putIfAbsent("error", null);
        return reply;
    }

    public java.time.Duration delay() {
        return properties.standalone().replyDelay();
    }

    private Map<String, Map<String, Object>> load() {
        Map<String, Map<String, Object>> loaded = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:standalone/replies/*.json");
            for (Resource resource : resources) {
                String name = resource.getFilename();
                if (name == null || !name.endsWith(".json")) {
                    continue;
                }
                try (InputStream input = resource.getInputStream()) {
                    loaded.put(name.substring(0, name.length() - 5), objectMapper.readValue(input, MAP));
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load canned replies", ex);
        }
        return Map.copyOf(loaded);
    }

    private static void copy(Map<String, Object> reply, String field, Object value) {
        if (!reply.containsKey(field) && value != null) {
            reply.put(field, value);
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
