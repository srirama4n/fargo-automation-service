package com.wf.fargo.automation.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("approvals")
public record Approval(
        @Id String approvalId,
        String runId,
        String agentId,
        String customerId,
        String previewHash,
        ApprovalStatus status,
        Instant expiresAt,
        String stepUpRef,
        String notificationId,
        Instant decidedAt,
        Instant createdAt,
        Instant updatedAt,
        int schemaVersion) {}
