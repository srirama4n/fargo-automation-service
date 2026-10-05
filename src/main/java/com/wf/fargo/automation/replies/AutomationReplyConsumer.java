package com.wf.fargo.automation.replies;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.runs.PoisonMessageException;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class AutomationReplyConsumer {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final ReplyHandler replies;

    public AutomationReplyConsumer(ObjectMapper objectMapper, ReplyHandler replies) {
        this.objectMapper = objectMapper;
        this.replies = replies;
    }

    @KafkaListener(topics = "${automation.topics.automation-replies}", groupId = "automation-replies")
    public void onReply(String body, Acknowledgment acknowledgment) {
        Map<String, Object> reply;
        try {
            reply = objectMapper.readValue(body, MAP);
        } catch (JsonProcessingException ex) {
            throw new PoisonMessageException("Unreadable reply", ex);
        }
        Object schemaVersion = reply.get("schemaVersion");
        if (!(schemaVersion instanceof Number number) || number.intValue() != 1) {
            throw new PoisonMessageException("Unknown reply schemaVersion");
        }
        replies.handle(reply);
        acknowledgment.acknowledge();
    }
}
