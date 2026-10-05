package com.wf.fargo.automation.standalone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.common.ApiException;
import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.domain.PersonalAgent;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"standalone", "test"})
public class DevTriggerController {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;
    private final CannedReplies replies;
    private final StubPushClient pushes;

    public DevTriggerController(
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper,
            AppProperties properties,
            MongoTemplate mongoTemplate,
            Clock clock,
            CannedReplies replies,
            StubPushClient pushes) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
        this.replies = replies;
        this.pushes = pushes;
    }

    @PostMapping("/v1/dev/trigger")
    public ResponseEntity<Map<String, Object>> trigger(@RequestBody(required = false) Map<String, Object> body)
            throws Exception {
        return ResponseEntity.accepted().body(publish(null, body));
    }

    @PostMapping("/v1/dev/trigger/{agentId}")
    public ResponseEntity<Map<String, Object>> triggerAgent(
            @PathVariable String agentId, @RequestBody(required = false) Map<String, Object> body) throws Exception {
        return ResponseEntity.accepted().body(publish(agentId, body));
    }

    @GetMapping("/v1/dev/pushes")
    public Map<String, Object> pushes() {
        return Map.of("pushes", pushes.sent());
    }

    @PostMapping("/v1/dev/replies/next")
    public ResponseEntity<Void> nextReply(@RequestBody(required = false) Map<String, Object> body) {
        replies.queueNext(body == null ? Map.of() : body);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> publish(String pathAgentId, Map<String, Object> body) throws Exception {
        Map<String, Object> request = body == null ? Map.of() : body;
        String agentId = first(pathAgentId, text(request.get("agentId")));
        if (agentId == null) {
            throw new ApiException(400, "validation_error", "agentId is required.");
        }
        PersonalAgent agent = mongoTemplate.findById(agentId, PersonalAgent.class);
        String periodId = text(request.get("periodId"));
        if (periodId == null) {
            throw new ApiException(400, "validation_error", "periodId is required.");
        }
        String customerId = first(text(request.get("customerId")), agent == null ? null : agent.customerId());
        if (customerId == null) {
            throw new ApiException(400, "validation_error", "customerId is required.");
        }
        String triggerType = first(
                text(request.get("triggerType")),
                agent != null && agent.trigger() != null ? agent.trigger().type() : null,
                "payroll_credit");
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("schemaVersion", number(request.get("schemaVersion"), 1));
        message.put("messageId", first(text(request.get("messageId")), Ulids.next(clock.millis())));
        message.put("occurredAt", first(text(request.get("occurredAt")), clock.instant().toString()));
        message.put("customerId", customerId);
        message.put("agentId", agentId);
        message.put("periodId", periodId);
        message.put("eventRef", first(text(request.get("eventRef")), "dev:" + periodId));
        message.put("triggerType", triggerType);
        Object context = request.get("context");
        message.put("context", context instanceof Map<?, ?> ? context : Map.of());
        kafka.send(properties.topics().triggerFired(), customerId, objectMapper.writeValueAsString(message))
                .get(10, TimeUnit.SECONDS);
        return message;
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
