package com.wf.fargo.automation.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wf.fargo.automation.AdjustableClock;
import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.agents.ConfirmationService;
import com.wf.fargo.automation.agents.PolicyChecker;
import com.wf.fargo.automation.approvals.ApprovalService;
import com.wf.fargo.automation.approvals.CvamsWireMock;
import com.wf.fargo.automation.common.ApiException;
import com.wf.fargo.automation.common.Hashing;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.Approval;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.replies.ReplyHandler;
import com.wf.fargo.automation.runs.RunService;
import com.wf.fargo.automation.runs.TriggerFixtures;
import com.wf.fargo.automation.store.IndexInitializer;
import com.wf.fargo.automation.sweeper.StuckRunJob;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class ObservabilityTest extends EmbeddedMongoTest {

    @Autowired
    private RunService runs;

    @Autowired
    private ReplyHandler replies;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private IndexInitializer indexInitializer;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private PrometheusMeterRegistry prometheus;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private PolicyChecker policy;

    @Autowired
    private ConfirmationService confirmations;

    @Autowired
    private StuckRunJob stuckRuns;

    @BeforeEach
    void indexes() {
        indexInitializer.ensureIndexes();
    }

    @Test
    void oneTraceIdIsVisibleFromTriggerToExecute() throws Exception {
        TriggerFixtures.seed(mongoTemplate(), "agt_1", AgentStatus.ON, 5);
        double transitions = count(AutomationMetrics.TRANSITIONS, "from", "triggered", "to", "previewing", "template", "payday_sweep");
        double decided = count(AutomationMetrics.APPROVALS, "decision", "approved");
        double duplicates = count(AutomationMetrics.DUPLICATES, "template", "payday_sweep");
        ListAppender<ILoggingEvent> logs = attach("com.wf.fargo.automation");
        try {
            runs.start(TriggerFixtures.fired("agt_1", "2026-10-15"));
            OutboxMessage preview = task("preview");
            String traceId = String.valueOf(preview.payload().get("traceId"));
            assertThat(traceId).matches("[0-9a-f]{32}");

            mongoTemplate().updateFirst(
                    Query.query(Criteria.where("_id").is(preview.messageId())),
                    new Update().set("sentAt", clock.instant().minusSeconds(2)).set("status", "sent"),
                    OutboxMessage.class);
            AgentRun run = mongoTemplate().findOne(Query.query(Criteria.where("agentId").is("agt_1")), AgentRun.class);
            replies.handle(Map.of(
                    "schemaVersion", 1,
                    "taskId", run.currentTaskId(),
                    "runId", run.runId(),
                    "customerId", "cust_1",
                    "phase", "preview",
                    "status", "ok",
                    "traceId", traceId,
                    "quote", Map.of("quoteId", "Q-1", "effect", "Move the extra payday money.", "warnings", List.of(), "data", Map.of())));

            Approval approval = mongoTemplate().findOne(Query.query(Criteria.where("runId").is(run.runId())), Approval.class);
            approvals.approve("cust_1", approval.approvalId(), CvamsWireMock.OK_TOKEN);

            OutboxMessage execute = task("execute");
            assertThat(execute.payload()).containsEntry("traceId", traceId);
            assertThat(execute.payload()).containsEntry("phase", "execute");
            assertThat(mongoTemplate().findById(run.runId(), AgentRun.class).state()).isEqualTo(RunState.EXECUTING);

            List<ILoggingEvent> traced = logs.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("run_transition"))
                    .filter(event -> traceId.equals(event.getMDCPropertyMap().get(LogContext.TRACE_ID)))
                    .toList();
            assertThat(traced).anyMatch(event -> event.getFormattedMessage().contains("to=PREVIEWING"));
            assertThat(traced).anyMatch(event -> event.getFormattedMessage().contains("to=EXECUTING"));
            assertThat(traced).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("cust_1");
                assertThat(event.getMDCPropertyMap().get(LogContext.CUSTOMER_ID)).isEqualTo(Hashing.sha256("cust_1"));
                assertThat(event.getMDCPropertyMap()).containsEntry(LogContext.AGENT_ID, "agt_1");
                assertThat(event.getMDCPropertyMap()).containsEntry(LogContext.RUN_ID, run.runId());
            });
        } finally {
            detach("com.wf.fargo.automation", logs);
        }

        assertThat(count(AutomationMetrics.TRANSITIONS, "from", "triggered", "to", "previewing", "template", "payday_sweep"))
                .isGreaterThan(transitions);
        assertThat(count(AutomationMetrics.TRANSITIONS, "from", "awaiting_approval", "to", "executing", "template", "payday_sweep"))
                .isPositive();
        assertThat(count(AutomationMetrics.APPROVALS, "decision", "approved")).isGreaterThan(decided);
        assertThat(registry.get(AutomationMetrics.APPROVAL_TIME).tag("template", "payday_sweep").timer().count()).isPositive();
        assertThat(registry.get(AutomationMetrics.DISPATCH_LATENCY).tag("phase", "preview").tag("mode", "kafka").timer().count())
                .isPositive();
        assertThat(registry.get(AutomationMetrics.OUTBOX_PENDING).tag("destination", "ORCHESTRATOR_TASK").gauge().value())
                .isGreaterThanOrEqualTo(1);
        assertThat(registry.get(AutomationMetrics.RUNS_IN_STATE).tag("state", "executing").gauge().value()).isGreaterThanOrEqualTo(1);
        assertThat(prometheus.scrape()).contains("automation_run_transitions_total");
        assertThat(prometheus.scrape()).contains("automation_approvals_decided_total");
        assertThat(prometheus.scrape()).contains("automation_dispatch_latency_seconds");

        runs.start(TriggerFixtures.fired("agt_1", "2026-10-15"));
        assertThat(count(AutomationMetrics.DUPLICATES, "template", "payday_sweep")).isGreaterThan(duplicates);
    }

    @Test
    void policyAndConfirmationRejectionsAreCounted() {
        double policyBefore = count(AutomationMetrics.POLICY, "code", "template_not_found");
        policy.check("cust_1", "missing", 1, Map.of("amount", 10));
        assertThat(count(AutomationMetrics.POLICY, "code", "template_not_found")).isGreaterThan(policyBefore);

        double confirmationBefore = count(AutomationMetrics.CONFIRMATION, "reason", "malformed");
        assertThatThrownBy(() -> confirmations.verify("cust_1", "not-a-token", "payday_sweep", 1, Map.of()))
                .isInstanceOf(ApiException.class);
        assertThat(count(AutomationMetrics.CONFIRMATION, "reason", "malformed")).isGreaterThan(confirmationBefore);
    }

    @Test
    void stuckPreviewIncrementsTheStuckCounter() {
        double before = count(AutomationMetrics.STUCK, "state", "previewing", "action", "retried");
        TriggerFixtures.seed(mongoTemplate(), "agt_1", AgentStatus.ON, null);
        Instant stuckAt = clock.instant().minus(Duration.ofMinutes(11));
        mongoTemplate().insert(new com.wf.fargo.automation.domain.AgentRun(
                "run_stuck",
                "agt_1",
                "cust_1",
                "period-1",
                "agt_1:period-1",
                "ref-1",
                Map.of(),
                RunState.PREVIEWING,
                List.of(),
                null,
                null,
                null,
                "tsk_stuck",
                "4bf92f3577b34da6a3ce929d0e0e4736",
                new com.wf.fargo.automation.domain.RunAttempts(1, 0),
                stuckAt,
                stuckAt,
                1));
        assertThat(stuckRuns.recover()).isEqualTo(1);
        assertThat(count(AutomationMetrics.STUCK, "state", "previewing", "action", "retried")).isGreaterThan(before);
        assertThat(task("preview").payload()).containsEntry("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void structuredLogLineCarriesTheTraceFields() {
        Logger logger = (Logger) LoggerFactory.getLogger("com.wf.fargo.automation.observe.JsonLogEncoder");
        LoggingEvent event = new LoggingEvent(
                JsonLogEncoder.class.getName(), logger, Level.INFO, "run_transition from=triggered to=previewing", null, null);
        event.setMDCPropertyMap(Map.of(
                LogContext.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736",
                LogContext.CUSTOMER_ID, Hashing.sha256("cust_1"),
                LogContext.AGENT_ID, "agt_1",
                LogContext.RUN_ID, "run_1"));
        JsonLogEncoder encoder = new JsonLogEncoder();
        encoder.start();
        String json = new String(encoder.encode(event), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json).contains("\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\"");
        assertThat(json).contains("\"customerId\":\"" + Hashing.sha256("cust_1") + "\"");
        assertThat(json).contains("\"agentId\":\"agt_1\"");
        assertThat(json).contains("\"runId\":\"run_1\"");
        assertThat(json).doesNotContain("cust_1");
    }

    private OutboxMessage task(String phase) {
        return mongoTemplate().findAll(OutboxMessage.class).stream()
                .filter(message -> message.destination() == OutboxDestination.ORCHESTRATOR_TASK)
                .filter(message -> phase.equals(message.payload().get("phase")))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private double count(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private static ListAppender<ILoggingEvent> attach(String name) {
        Logger logger = (Logger) LoggerFactory.getLogger(name);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(String name, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(name)).detachAppender(appender);
    }
}
