package com.wf.fargo.automation.domain;

import java.util.List;
import java.util.Map;

public record AgentTrigger(
        String type, List<String> accountScopes, Map<String, Object> filters, TriggerSchedule schedule) {}
