package com.wf.fargo.automation.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("confirmations")
public record Confirmation(
        @Id String confirmationId,
        String customerId,
        String draftHash,
        Instant usedAt,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        int schemaVersion) {}
