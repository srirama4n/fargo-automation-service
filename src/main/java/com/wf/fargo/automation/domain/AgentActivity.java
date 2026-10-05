package com.wf.fargo.automation.domain;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("agent_activity")
public record AgentActivity(
        @Id String eventId,
        String customerId,
        String agentId,
        String runId,
        ActivityType type,
        String message,
        Map<String, Object> data,
        Instant createdAt,
        Instant updatedAt,
        int schemaVersion) {}
