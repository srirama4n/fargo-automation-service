package com.wf.fargo.automation.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class RunStateMachine {

    private static final Map<RunState, Set<RunState>> ALLOWED = Map.of(
            RunState.TRIGGERED, Set.copyOf(EnumSet.of(RunState.PREVIEWING, RunState.SKIPPED)),
            RunState.PREVIEWING, Set.copyOf(EnumSet.of(
                    RunState.AWAITING_APPROVAL, RunState.EXECUTING, RunState.DONE, RunState.FAILED)),
            RunState.AWAITING_APPROVAL, Set.copyOf(EnumSet.of(RunState.EXECUTING, RunState.DENIED, RunState.EXPIRED)),
            RunState.EXECUTING, Set.copyOf(EnumSet.of(RunState.DONE, RunState.FAILED)));

    private RunStateMachine() {}

    public static boolean canTransition(RunState from, RunState to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }
}
