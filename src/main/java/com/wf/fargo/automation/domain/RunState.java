package com.wf.fargo.automation.domain;

public enum RunState {
    TRIGGERED,
    PREVIEWING,
    AWAITING_APPROVAL,
    EXECUTING,
    DONE,
    SKIPPED,
    DENIED,
    EXPIRED,
    FAILED
}
