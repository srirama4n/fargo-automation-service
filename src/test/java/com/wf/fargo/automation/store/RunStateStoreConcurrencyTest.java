package com.wf.fargo.automation.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.RunAttempts;
import com.wf.fargo.automation.domain.RunState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RunStateStoreConcurrencyTest extends EmbeddedMongoTest {

    private static final int REPETITIONS = 100;
    private static final int THREADS = 50;

    @Autowired
    private RunStateStore runStateStore;

    @Autowired
    private AgentRunRepository agentRuns;

    @Test
    void exactlyOneTransitionWinsAcrossOneHundredRaces() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int repetition = 0; repetition < REPETITIONS; repetition++) {
                String runId = "run_" + repetition;
                agentRuns.save(awaitingApproval(runId));

                List<Future<Optional<AgentRun>>> futures = new ArrayList<>();
                for (int thread = 0; thread < THREADS; thread++) {
                    RunState target = thread < THREADS / 2 ? RunState.EXECUTING : RunState.EXPIRED;
                    futures.add(pool.submit(() -> runStateStore.transition(
                            runId, RunState.AWAITING_APPROVAL, target, "race", null)));
                }

                long wins = 0;
                for (Future<Optional<AgentRun>> future : futures) {
                    if (future.get(30, TimeUnit.SECONDS).isPresent()) {
                        wins++;
                    }
                }
                assertThat(wins).as("repetition %s", repetition).isEqualTo(1);

                AgentRun stored = agentRuns.findById(runId).orElseThrow();
                assertThat(stored.state()).isIn(RunState.EXECUTING, RunState.EXPIRED);
                assertThat(stored.stateHistory()).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static AgentRun awaitingApproval(String runId) {
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        return new AgentRun(
                runId,
                "agt_1",
                "cust_1",
                "2026-10-15",
                "agt_1:2026-10-15-" + runId,
                "post_1",
                null,
                RunState.AWAITING_APPROVAL,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                new RunAttempts(1, 0),
                now,
                now,
                1);
    }
}
