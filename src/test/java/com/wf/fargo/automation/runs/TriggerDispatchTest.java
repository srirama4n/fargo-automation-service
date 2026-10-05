package com.wf.fargo.automation.runs;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.domain.StateHistoryEntry;
import com.wf.fargo.automation.store.IndexInitializer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

class TriggerDispatchTest extends EmbeddedMongoTest {

    @Autowired
    private RunService runs;

    @Autowired
    private IndexInitializer indexInitializer;

    @BeforeEach
    void indexes() {
        indexInitializer.ensureIndexes();
    }

    @Test
    void duplicateTriggerKeepsOnePreviewingRunAndOneKafkaTask() {
        TriggerFixtures.seed(mongoTemplate(), "agt_1", AgentStatus.ON, 5);
        TriggerFired first = TriggerFixtures.fired("agt_1", "2026-10-15");
        runs.start(first);
        runs.start(first);

        List<AgentRun> stored = mongoTemplate().find(Query.query(Criteria.where("agentId").is("agt_1")), AgentRun.class);
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().state()).isEqualTo(RunState.PREVIEWING);
        assertThat(stored.getFirst().idempotencyKey()).isEqualTo("agt_1:2026-10-15");
        assertThat(tasks()).singleElement().satisfies(message -> {
            assertThat(message.destination()).isEqualTo(OutboxDestination.ORCHESTRATOR_TASK);
            assertThat(message.payload()).containsEntry("phase", "preview");
            assertThat(message.payload()).containsEntry("intent", "transfer.preview");
            assertThat(message.payload()).containsEntry("channel", "automation");
            assertThat(message.payload()).doesNotContainKey("grantToken");
            assertThat(message.payload().get("taskId")).isEqualTo(stored.getFirst().currentTaskId());
        });
    }

    @Test
    void pausedAgentIsSkippedWithoutATask() {
        TriggerFixtures.seed(mongoTemplate(), "agt_1", AgentStatus.PAUSED, null);
        runs.start(TriggerFixtures.fired("agt_1", "2026-10-15"));

        AgentRun run = mongoTemplate().findOne(Query.query(Criteria.where("agentId").is("agt_1")), AgentRun.class);
        assertThat(run).isNotNull();
        assertThat(run.state()).isEqualTo(RunState.SKIPPED);
        assertThat(run.stateHistory()).extracting(StateHistoryEntry::reason).contains("agent_paused");
        assertThat(tasks()).isEmpty();
    }

    @Test
    void dailyLimitSkipsTheNextRunToday() {
        TriggerFixtures.seed(mongoTemplate(), "agt_1", AgentStatus.ON, 1);
        runs.start(TriggerFixtures.fired("agt_1", "2026-10-15"));
        runs.start(TriggerFixtures.fired("agt_1", "2026-10-16"));

        List<AgentRun> stored = mongoTemplate().find(Query.query(Criteria.where("agentId").is("agt_1")), AgentRun.class);
        assertThat(stored).hasSize(2);
        assertThat(stored).filteredOn(run -> run.periodId().equals("2026-10-15"))
                .singleElement()
                .extracting(AgentRun::state)
                .isEqualTo(RunState.PREVIEWING);
        assertThat(stored).filteredOn(run -> run.periodId().equals("2026-10-16"))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.state()).isEqualTo(RunState.SKIPPED);
                    assertThat(run.stateHistory()).extracting(StateHistoryEntry::reason).contains("daily_limit");
                });
        assertThat(tasks()).hasSize(1);
    }

    @Test
    void missingAgentIsAcknowledgedWithoutARun() {
        runs.start(new TriggerFired(1, "m1", Instant.parse("2026-10-15T13:05:00Z"), "cust_1", "agt_missing",
                "2026-10-15", "post_1", "payroll_credit", Map.of()));
        assertThat(mongoTemplate().findAll(AgentRun.class)).isEmpty();
    }

    private List<OutboxMessage> tasks() {
        return mongoTemplate().find(
                Query.query(Criteria.where("destination").in(
                        OutboxDestination.ORCHESTRATOR_TASK, OutboxDestination.A2A_TASK)),
                OutboxMessage.class);
    }
}
