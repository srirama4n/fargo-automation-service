package com.wf.fargo.automation.runs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class TriggerFiredConsumer {

    private final ObjectMapper objectMapper;
    private final RunService runs;

    public TriggerFiredConsumer(ObjectMapper objectMapper, RunService runs) {
        this.objectMapper = objectMapper;
        this.runs = runs;
    }

    @KafkaListener(topics = "${automation.topics.trigger-fired}", groupId = "automation-service")
    public void onTrigger(String body, Acknowledgment acknowledgment) {
        TriggerFired trigger;
        try {
            trigger = objectMapper.readValue(body, TriggerFired.class);
        } catch (JsonProcessingException ex) {
            throw new PoisonMessageException("Unreadable trigger", ex);
        }
        if (trigger.schemaVersion() != 1) {
            throw new PoisonMessageException("Unknown trigger schemaVersion");
        }
        runs.start(trigger);
        acknowledgment.acknowledge();
    }
}
