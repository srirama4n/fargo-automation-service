package com.wf.fargo.automation.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("admin_jobs")
public record AdminJob(
        @Id String jobId,
        String templateId,
        String reason,
        boolean expirePending,
        String admin,
        int paused,
        int batches,
        String status,
        Instant createdAt,
        Instant updatedAt,
        int schemaVersion) {}
