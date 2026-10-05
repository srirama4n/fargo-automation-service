package com.wf.fargo.automation.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxDeliverer {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;
    private final PushClient pushClient;
    private final GrantTokenMinter grantTokenMinter;
    private final A2aTaskDeliverer a2a;

    public OutboxDeliverer(
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper,
            AppProperties properties,
            PushClient pushClient,
            @Autowired(required = false) GrantTokenMinter grantTokenMinter,
            @Autowired(required = false) A2aTaskDeliverer a2a) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.pushClient = pushClient;
        this.grantTokenMinter = grantTokenMinter;
        this.a2a = a2a;
    }

    public void deliver(OutboxMessage message) {
        if (message.destination() == OutboxDestination.A2A_TASK) {
            if (a2a == null) {
                throw new IllegalStateException("A2A delivery is not available");
            }
            a2a.deliver(copy(message, outboundPayload(message)));
            return;
        }
        if (message.destination() == OutboxDestination.PUSH) {
            pushClient.send(outboundPayload(message));
            return;
        }
        sendKafka(message);
    }

    private void sendKafka(OutboxMessage message) {
        String json = json(outboundPayload(message));
        try {
            kafka.send(topic(message.destination()), message.key(), json).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka send interrupted", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("Kafka send failed", ex);
        }
    }

    private Map<String, Object> outboundPayload(OutboxMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (message.payload() != null) {
            body.putAll(message.payload());
        }
        if (message.destination() == OutboxDestination.ORCHESTRATOR_TASK
                || message.destination() == OutboxDestination.A2A_TASK) {
            if (grantTokenMinter == null) {
                throw new IllegalStateException("Grant token minter is not available");
            }
            body.put("grantToken", grantTokenMinter.mint(message.payload()));
        }
        return body;
    }

    private static OutboxMessage copy(OutboxMessage message, Map<String, Object> payload) {
        return new OutboxMessage(
                message.messageId(),
                message.destination(),
                message.key(),
                payload,
                message.status(),
                message.attempts(),
                message.nextAttemptAt(),
                message.lockedBy(),
                message.lockedUntil(),
                message.sentAt(),
                message.createdAt(),
                message.updatedAt(),
                message.schemaVersion());
    }

    private String topic(OutboxDestination destination) {
        return switch (destination) {
            case AGENT_CONTROL -> properties.topics().agentControl();
            case BASELINE_UPDATES -> properties.topics().baselineUpdates();
            case ORCHESTRATOR_TASK -> properties.topics().orchestratorInput();
            case PUSH, A2A_TASK -> throw new IllegalStateException("No Kafka topic for " + destination);
        };
    }

    private String json(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize outbox payload", ex);
        }
    }
}
