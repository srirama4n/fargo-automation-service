package com.wf.fargo.automation.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.domain.ActivityType;
import com.wf.fargo.automation.domain.AgentActivity;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.RunAttempts;
import com.wf.fargo.automation.domain.RunState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TransactionRollbackTest extends EmbeddedMongoTest {

    @Autowired
    private TransactionRunner transactionRunner;

    @Autowired
    private RunStateStore runStateStore;

    @Autowired
    private AgentRunRepository agentRuns;

    @Autowired
    private AgentActivityRepository activities;

    @Autowired
    private OutboxMessageRepository outbox;

    @Test
    void exceptionRollsBackStateChangeActivityAndOutboxTogether() {
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        agentRuns.save(new AgentRun(
                "run_1",
                "agt_1",
                "cust_1",
                "2026-10-15",
                "agt_1:2026-10-15",
                "post_1",
                null,
                RunState.TRIGGERED,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                new RunAttempts(0, 0),
                now,
                now,
                1));

        assertThatThrownBy(() -> transactionRunner.inTransaction(() -> {
            runStateStore.transition("run_1", RunState.TRIGGERED, RunState.PREVIEWING, "start", null);
            activities.save(new AgentActivity(
                    "evt_1", "cust_1", "agt_1", "run_1", ActivityType.FIRED, "Fired", null, now, now, 1));
            outbox.save(new OutboxMessage(
                    "msg_1",
                    OutboxDestination.ORCHESTRATOR_TASK,
                    "cust_1",
                    Map.of("phase", "preview"),
                    "pending",
                    0,
                    now,
                    null,
                    null,
                    null,
                    now,
                    now,
                    1));
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        AgentRun stored = agentRuns.findById("run_1").orElseThrow();
        assertThat(stored.state()).isEqualTo(RunState.TRIGGERED);
        assertThat(stored.stateHistory()).isEmpty();
        assertThat(activities.findAll()).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
    }
}
