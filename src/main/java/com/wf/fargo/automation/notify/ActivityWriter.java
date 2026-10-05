package com.wf.fargo.automation.notify;

import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.domain.ActivityType;
import com.wf.fargo.automation.domain.AgentActivity;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ActivityWriter {

    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public ActivityWriter(MongoTemplate mongoTemplate, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    public AgentActivity write(
            String customerId,
            String agentId,
            String runId,
            ActivityType type,
            String message,
            Map<String, Object> data) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("ActivityWriter.write must run inside a transaction");
        }
        if (customerId == null || agentId == null || type == null || message == null) {
            throw new IllegalArgumentException("customerId, agentId, type, and message are required");
        }
        Instant now = clock.instant();
        AgentActivity event = new AgentActivity(
                "evt_" + Ulids.next(clock.millis()),
                customerId,
                agentId,
                runId,
                type,
                message,
                data == null ? Map.of() : data,
                now,
                now,
                1);
        mongoTemplate.insert(event);
        return event;
    }
}
