package com.wf.fargo.automation.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("agent_templates")
public record AgentTemplate(
        @Id String id,
        String templateId,
        int version,
        String name,
        String description,
        TemplateTrigger trigger,
        TemplateAction action,
        AgentMode mode,
        Map<String, Object> paramSchema,
        TemplateLimits limits,
        List<String> requiredTools,
        Duration approvalTtl,
        String effectText,
        String status,
        String reviewedBy,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt,
        int schemaVersion) {}
