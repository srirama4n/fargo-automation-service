package com.wf.fargo.automation.sweeper;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wf.fargo.automation.AdjustableClock;
import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.domain.ActivityType;
import com.wf.fargo.automation.domain.AgentActivity;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentStatus;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

class StuckRunJobTest extends EmbeddedMongoTest {

    private static final String RUN = "run_stuck";
    private static final String TASK = "tsk_old";

    @Autowired
    private StuckRunJob job;

    @Autowired
    private AdjustableClock clock;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = capture(StuckRunJob.class.getName());
    }

    @AfterEach
    void detachLogs() {
        ((Logger) LoggerFactory.getLogger(StuckRunJob.class)).detachAppender(logs);
    }

    @Test
    void stuckPreviewIsRetriedWithANewTaskId() {
        Instant stuckAt = clock.instant().minus(Duration.ofMinutes(11));
        seed(RunState.PREVIEWING, null, new RunAttempts(1, 0), stuckAt);

        assertThat(job.recover()).isEqualTo(1);
        assertThat(job.recover()).isEqualTo(0);

        AgentRun run = run();
        assertThat(run.state()).isEqualTo(RunState.PREVIEWING);
        assertThat(run.attempts().preview()).isEqualTo(2);
        assertThat(run.attempts().execute()).isEqualTo(0);
        assertThat(run.currentTaskId()).isNotEqualTo(TASK);
        assertThat(tasks("preview")).singleElement().satisfies(message -> {
            assertThat(message.payload()).containsEntry("phase", "preview");
            assertThat(message.payload()).containsEntry("taskId", run.currentTaskId());
            assertThat(message.payload()).doesNotContainKey("grantToken");
            assertThat(message.nextAttemptAt()).isBefore(clock.instant().plusSeconds(1));
        });
        assertThat(tasks("execute")).isEmpty();
    }

    @Test
    void stuckPreviewFailsWhenAttemptsAreExhausted() {
        seed(RunState.PREVIEWING, null, new RunAttempts(3, 0), clock.instant().minus(Duration.ofMinutes(11)));

        assertThat(job.recover()).isEqualTo(1);

        AgentRun run = run();
        assertThat(run.state()).isEqualTo(RunState.FAILED);
        assertThat(run.error().code()).isEqualTo("preview_timeout");
        assertThat(tasks("preview")).isEmpty();
        assertThat(pushes()).singleElement().satisfies(message ->
                assertThat(message.payload()).containsEntry("body", "We couldn't complete this. Nothing was changed."));
        assertThat(activities()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(ActivityType.FAILED);
            assertThat(event.message()).isEqualTo("We couldn't complete this. Nothing was changed.");
        });
    }

    @Test
    void stuckExecuteIsResentWithTheSameQuoteId() {
        seed(
                RunState.EXECUTING,
                new RunPreview("Q-9", "Move the extra payday money.", List.of(), Map.of()),
                new RunAttempts(1, 1),
                clock.instant().minus(Duration.ofMinutes(11)));

        assertThat(job.recover()).isEqualTo(1);

        AgentRun run = run();
        assertThat(run.state()).isEqualTo(RunState.EXECUTING);
        assertThat(run.preview().quoteId()).isEqualTo("Q-9");
        assertThat(run.attempts().execute()).isEqualTo(2);
        assertThat(run.currentTaskId()).isNotEqualTo(TASK);
        assertThat(tasks("execute")).singleElement().satisfies(message -> {
            assertThat(message.payload()).containsEntry("phase", "execute");
            assertThat(message.payload()).containsEntry("quoteId", "Q-9");
            assertThat(message.payload()).containsEntry("taskId", run.currentTaskId());
            assertThat(message.payload()).doesNotContainKey("grantToken");
            assertThat(message.nextAttemptAt()).isBefore(clock.instant().plusSeconds(1));
        });
        assertThat(tasks("preview")).isEmpty();
    }

    @Test
    void stuckExecuteFailsAndAlertsWhenAttemptsAreExhausted() {
        seed(
                RunState.EXECUTING,
                new RunPreview("Q-9", "Move the extra payday money.", List.of(), Map.of()),
                new RunAttempts(1, 3),
                clock.instant().minus(Duration.ofMinutes(11)));

        assertThat(job.recover()).isEqualTo(1);

        AgentRun run = run();
        assertThat(run.state()).isEqualTo(RunState.FAILED);
        assertThat(run.error().code()).isEqualTo("execute_timeout");
        assertThat(tasks("execute")).isEmpty();
        assertThat(pushes()).singleElement().satisfies(message ->
                assertThat(message.payload()).containsEntry("body", "We couldn't complete this."));
        assertThat(activities()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(ActivityType.FAILED);
            assertThat(event.message()).isEqualTo("We couldn't complete this.");
        });
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.ERROR
                && event.getFormattedMessage().contains("execute_timeout")
                && event.getFormattedMessage().contains(RUN));
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("cust_1")
                || event.getFormattedMessage().contains("Q-9"));
    }

    @Test
    void recentPreviewIsLeftAlone() {
        seed(RunState.PREVIEWING, null, new RunAttempts(1, 0), clock.instant().minus(Duration.ofMinutes(9)));

        assertThat(job.recover()).isEqualTo(0);

        assertThat(run().currentTaskId()).isEqualTo(TASK);
        assertThat(run().attempts().preview()).isEqualTo(1);
        assertThat(mongoTemplate().findAll(OutboxMessage.class)).isEmpty();
    }

    private void seed(RunState state, RunPreview preview, RunAttempts attempts, Instant updatedAt) {
        TriggerFixtures.seed(mongoTemplate(), "agt_1", AgentStatus.ON, null);
        mongoTemplate().insert(new AgentRun(
                RUN,
                "agt_1",
                "cust_1",
                "period-1",
                "agt_1:period-1",
                "ref-1",
                Map.of(),
                state,
                List.of(),
                preview,
                null,
                null,
                TASK,
                null,
                attempts,
                updatedAt,
                updatedAt,
                1));
    }

    private AgentRun run() {
        AgentRun run = mongoTemplate().findById(RUN, AgentRun.class);
        assertThat(run).isNotNull();
        return run;
    }

    private List<OutboxMessage> tasks(String phase) {
        return mongoTemplate().findAll(OutboxMessage.class).stream()
                .filter(message -> message.destination() == OutboxDestination.ORCHESTRATOR_TASK)
                .filter(message -> phase.equals(message.payload().get("phase")))
                .toList();
    }

    private List<OutboxMessage> pushes() {
        return mongoTemplate().findAll(OutboxMessage.class).stream()
                .filter(message -> message.destination() == OutboxDestination.PUSH)
                .toList();
    }

    private List<AgentActivity> activities() {
        return mongoTemplate().findAll(AgentActivity.class);
    }

    private static ListAppender<ILoggingEvent> capture(String name) {
        Logger logger = (Logger) LoggerFactory.getLogger(name);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
