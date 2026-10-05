package com.wf.fargo.automation.domain;

import java.time.Instant;

public record StateHistoryEntry(RunState from, RunState to, Instant at, String reason) {}
