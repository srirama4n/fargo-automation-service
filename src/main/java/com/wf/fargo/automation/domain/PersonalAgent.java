package com.wf.fargo.automation.domain;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("personal_agents")
public record PersonalAgent(
        @Id String agentId,
        String customerId,
        String templateId,
        int templateVersion,
        String name,
        Map<String, Object> params,
        AgentTrigger trigger,
        FireSchedule schedule,
        Guards guards,
        AgentMode mode,
        AgentStatus status,
        String grantId,
        String sourceUtterance,
        String sessionId,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt,
        int schemaVersion) {

    public PersonalAgent withVersion(Long version) {
        return new PersonalAgent(
                agentId,
                customerId,
                templateId,
                templateVersion,
                name,
                params,
                trigger,
                schedule,
                guards,
                mode,
                status,
                grantId,
                sourceUtterance,
                sessionId,
                version,
                createdAt,
                updatedAt,
                schemaVersion);
    }
}
