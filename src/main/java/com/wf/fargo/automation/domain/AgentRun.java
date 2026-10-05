package com.wf.fargo.automation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("agent_runs")
public record AgentRun(
        @Id String runId,
        String agentId,
        String customerId,
        String periodId,
        String idempotencyKey,
        String triggerEventRef,
        Map<String, Object> triggerContext,
        RunState state,
        List<StateHistoryEntry> stateHistory,
        RunPreview preview,
        Map<String, Object> outcome,
        RunError error,
        String currentTaskId,
        String traceId,
        RunAttempts attempts,
        Instant createdAt,
        Instant updatedAt,
        int schemaVersion) {

    public AgentRun {
        stateHistory = stateHistory == null ? List.of() : stateHistory;
    }
}
