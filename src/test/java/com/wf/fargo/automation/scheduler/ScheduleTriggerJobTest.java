package com.wf.fargo.automation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wf.fargo.automation.AdjustableClock;
import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.AgentTrigger;
import com.wf.fargo.automation.domain.FireSchedule;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.domain.TriggerSchedule;
import com.wf.fargo.automation.runs.RunService;
import com.wf.fargo.automation.runs.TriggerFixtures;
import com.wf.fargo.automation.store.IndexInitializer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class ScheduleTriggerJobTest extends EmbeddedMongoTest {

    private static final String AGENT = "agt_sched";
    private static final String CRON = "0 0 9 * * *";
    private static final String ZONE = "America/New_York";

    @Autowired
    private ScheduleTriggerJob job;

    @Autowired
    private ScheduleCalculator calculator;

    @Autowired
    private IndexInitializer indexInitializer;

    @Autowired
    private AdjustableClock clock;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void indexes() {
        indexInitializer.ensureIndexes();
        logs = capture(RunService.class.getName());
    }

    @AfterEach
    void detachLogs() {
        ((Logger) LoggerFactory.getLogger(RunService.class)).detachAppender(logs);
    }

    @Test
    void tickFiresTheDueOccurrenceOnceAndMovesNextFireAtForward() {
        Instant occurrence = clock.instant().minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.MILLIS);
        seedDue(occurrence, AgentStatus.ON);
        Instant before = clock.instant();

        assertThat(job.tick()).isEqualTo(1);
        assertThat(job.tick()).isEqualTo(0);

        AgentRun run = onlyRun();
        assertThat(run.state()).isEqualTo(RunState.PREVIEWING);
        assertThat(run.periodId()).isEqualTo(occurrence.toString());
        assertThat(run.idempotencyKey()).isEqualTo(AGENT + ":" + occurrence);
        assertThat(run.triggerEventRef()).isEqualTo("schedule:" + occurrence);
        Instant stored = agent().schedule().nextFireAt();
        assertThat(stored).isIn(
                calculator.nextFireAt(CRON, ZONE, before),
                calculator.nextFireAt(CRON, ZONE, clock.instant()));
        assertThat(previewTasks()).hasSize(1);
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("duplicate_trigger"));
    }

    @Test
    void twoInstancesClaimTheSameOccurrenceOnce() throws Exception {
        Instant occurrence = clock.instant().minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.MILLIS);
        seedDue(occurrence, AgentStatus.ON);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Callable<Integer> claim = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return job.tick();
            };
            Future<Integer> first = pool.submit(claim);
            Future<Integer> second = pool.submit(claim);
            int fired = first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS);
            assertThat(fired).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(mongoTemplate().findAll(AgentRun.class)).hasSize(1);
        AgentRun run = onlyRun();
        assertThat(run.periodId()).isEqualTo(occurrence.toString());
        assertThat(run.triggerEventRef()).startsWith("schedule:");
        assertThat(run.state()).isEqualTo(RunState.PREVIEWING);
        assertThat(previewTasks()).hasSize(1);
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("duplicate_trigger"));
    }

    @Test
    void pausedAgentIsNotClaimed() {
        seedDue(clock.instant().minus(Duration.ofMinutes(5)), AgentStatus.PAUSED);

        assertThat(job.tick()).isEqualTo(0);
        assertThat(mongoTemplate().findAll(AgentRun.class)).isEmpty();
    }

    private void seedDue(Instant occurrence, AgentStatus status) {
        TriggerFixtures.seed(mongoTemplate(), AGENT, status, null);
        mongoTemplate().updateFirst(
                Query.query(Criteria.where("_id").is(AGENT)),
                new Update()
                        .set("trigger", new AgentTrigger(
                                "schedule", List.of("acct_tok_chk"), Map.of(), new TriggerSchedule(CRON, ZONE)))
                        .set("schedule", new FireSchedule(occurrence)),
                PersonalAgent.class);
    }

    private AgentRun onlyRun() {
        List<AgentRun> runs = mongoTemplate().findAll(AgentRun.class);
        assertThat(runs).hasSize(1);
        return runs.getFirst();
    }

    private PersonalAgent agent() {
        PersonalAgent agent = mongoTemplate().findById(AGENT, PersonalAgent.class);
        assertThat(agent).isNotNull();
        return agent;
    }

    private List<OutboxMessage> previewTasks() {
        return mongoTemplate().findAll(OutboxMessage.class).stream()
                .filter(message -> message.destination() == OutboxDestination.ORCHESTRATOR_TASK)
                .filter(message -> "preview".equals(message.payload().get("phase")))
                .toList();
    }

    private static ListAppender<ILoggingEvent> capture(String name) {
        Logger logger = (Logger) LoggerFactory.getLogger(name);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
