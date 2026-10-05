package com.wf.fargo.automation.sweeper;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.fargo.automation.AdjustableClock;
import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.domain.ActivityType;
import com.wf.fargo.automation.domain.AgentActivity;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.Approval;
import com.wf.fargo.automation.domain.ApprovalStatus;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.RunAttempts;
import com.wf.fargo.automation.domain.RunPreview;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.runs.TriggerFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ApprovalExpiryJobTest extends EmbeddedMongoTest {

    @Autowired
    private ApprovalExpiryJob job;

    @Autowired
    private LockProvider lockProvider;

    @Autowired
    private AdjustableClock clock;

    @Test
    void expireDueExpiresOnlyApprovalsWhoseDeadlineHasPassed() {
        Instant now = clock.instant();
        seed("apr_due", "run_due", "due", now.minus(Duration.ofHours(1)), now.minus(Duration.ofHours(25)));
        seed("apr_open", "run_open", "open", now.plus(Duration.ofHours(2)), now);

        assertThat(job.expireDue()).isEqualTo(1);

        assertThat(approval("apr_due").status()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(run("run_due").state()).isEqualTo(RunState.EXPIRED);
        assertThat(approval("apr_open").status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(run("run_open").state()).isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(pushes()).singleElement().satisfies(message ->
                assertThat(message.payload()).containsEntry("body", "This request expired, nothing was changed."));
        assertThat(mongoTemplate().findAll(AgentActivity.class)).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo(ActivityType.EXPIRED);
            assertThat(event.runId()).isEqualTo("run_due");
        });
    }

    @Test
    void heldLockSkipsSweepWhileExpireDueStillRuns() {
        Instant now = clock.instant();
        seed("apr_due", "run_due", "due", now.minus(Duration.ofMinutes(1)), now.minus(Duration.ofHours(25)));
        Optional<SimpleLock> held = lockProvider.lock(new LockConfiguration(
                Instant.now(), "approvalExpiry", Duration.ofMinutes(5), Duration.ZERO));
        assertThat(held).isPresent();
        try {
            job.sweep();
            assertThat(approval("apr_due").status()).isEqualTo(ApprovalStatus.PENDING);
            assertThat(job.expireDue()).isEqualTo(1);
            assertThat(approval("apr_due").status()).isEqualTo(ApprovalStatus.EXPIRED);
        } finally {
            held.get().unlock();
        }
    }

    private void seed(String approvalId, String runId, String periodId, Instant expiresAt, Instant updatedAt) {
        if (mongoTemplate().findById("agt_1", com.wf.fargo.automation.domain.PersonalAgent.class) == null) {
            TriggerFixtures.seed(mongoTemplate(), "agt_1", AgentStatus.ON, null);
        }
        mongoTemplate().insert(new AgentRun(
                runId,
                "agt_1",
                "cust_1",
                periodId,
                "agt_1:" + periodId,
                "ref-" + periodId,
                Map.of(),
                RunState.AWAITING_APPROVAL,
                List.of(),
                new RunPreview("Q-1", "Move the extra payday money.", List.of(), Map.of()),
                null,
                null,
                "tsk_" + periodId,
                null,
                new RunAttempts(1, 0),
                updatedAt,
                updatedAt,
                1));
        mongoTemplate().insert(new Approval(
                approvalId,
                runId,
                "agt_1",
                "cust_1",
                "hash",
                ApprovalStatus.PENDING,
                expiresAt,
                null,
                null,
                null,
                updatedAt,
                updatedAt,
                1));
    }

    private Approval approval(String approvalId) {
        Approval approval = mongoTemplate().findById(approvalId, Approval.class);
        assertThat(approval).isNotNull();
        return approval;
    }

    private AgentRun run(String runId) {
        AgentRun run = mongoTemplate().findById(runId, AgentRun.class);
        assertThat(run).isNotNull();
        return run;
    }

    private List<OutboxMessage> pushes() {
        return mongoTemplate().findAll(OutboxMessage.class).stream()
                .filter(message -> message.destination() == OutboxDestination.PUSH)
                .toList();
    }
}
