package com.wf.fargo.automation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("grants")
public record Grant(
        @Id String grantId,
        String agentId,
        String customerId,
        List<String> allowedTools,
        List<String> accountScopes,
        Map<String, Object> limits,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt,
        String revokedReason,
        Instant createdAt,
        Instant updatedAt,
        int schemaVersion) {}
