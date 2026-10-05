package com.wf.fargo.automation.runs;

import java.time.Instant;
import java.util.Map;

public record TriggerFired(
        int schemaVersion,
        String messageId,
        Instant occurredAt,
        String customerId,
        String agentId,
        String periodId,
        String eventRef,
        String triggerType,
        Map<String, Object> context) {}
