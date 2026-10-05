package com.wf.fargo.automation.domain;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("outbox")
public record OutboxMessage(
        @Id String messageId,
        OutboxDestination destination,
        String key,
        Map<String, Object> payload,
        String status,
        int attempts,
        Instant nextAttemptAt,
        String lockedBy,
        Instant lockedUntil,
        Instant sentAt,
        Instant createdAt,
        Instant updatedAt,
        int schemaVersion) {}
