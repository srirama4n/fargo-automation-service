package com.wf.fargo.automation.domain;

public enum OutboxDestination {
    AGENT_CONTROL,
    BASELINE_UPDATES,
    ORCHESTRATOR_TASK,
    A2A_TASK,
    PUSH
}
