package com.wf.fargo.automation.approvals;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wf.fargo.automation.AdjustableClock;
import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.common.CanonicalJson;
import com.wf.fargo.automation.common.Hashing;
import com.wf.fargo.automation.domain.ActivityType;
import com.wf.fargo.automation.domain.AgentActivity;
import com.wf.fargo.automation.domain.AgentMode;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.domain.AgentTrigger;
import com.wf.fargo.automation.domain.Approval;
import com.wf.fargo.automation.domain.ApprovalStatus;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.RunAttempts;
import com.wf.fargo.automation.domain.RunPreview;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.domain.TemplateAction;
import com.wf.fargo.automation.domain.TemplateLimits;
import com.wf.fargo.automation.domain.TemplateTrigger;
import com.wf.fargo.automation.standalone.DevAuthFilter;
import com.wf.fargo.automation.store.IndexInitializer;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class ApprovalEndpointTest extends EmbeddedMongoTest {

    private static final Instant SEEDED = Instant.parse("2026-10-01T00:00:00Z");
    private static final String CUSTOMER = "cust_1";
    private static final String APPROVAL = "apr_1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private IndexInitializer indexInitializer;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private AdjustableClock clock;

    @BeforeEach
    void indexes() {
        indexInitializer.ensureIndexes();
        seed();
    }

    @Test
    void getReturnsThePendingApprovalWithoutTheQuoteId() throws Exception {
        mvc.perform(get("/v1/approvals/" + APPROVAL).header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalId").value(APPROVAL))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.agent.name").value("Payday sweep"))
                .andExpect(jsonPath("$.trigger.amount").value(1000))
                .andExpect(jsonPath("$.preview.effect").value("Move the extra payday money."))
                .andExpect(jsonPath("$.preview.warnings").isArray())
                .andExpect(jsonPath("$.preview.data").isMap())
                .andExpect(jsonPath("$.preview.quoteId").doesNotExist());
    }

    @Test
    void getForAnotherCustomerIsNotFound() throws Exception {
        ListAppender<ILoggingEvent> logs = capture("com.wf.fargo.automation.approvals.ApprovalService");
        mvc.perform(get("/v1/approvals/" + APPROVAL).header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("security=true")
                && event.getFormattedMessage().contains("ownership_mismatch"));
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("cust_9")
                || event.getFormattedMessage().contains(CUSTOMER));
        mvc.perform(get("/v1/approvals/apr_missing").header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void approveMovesTheRunToExecuting() throws Exception {
        mvc.perform(post("/v1/approvals/" + APPROVAL + "/approve")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header("X-StepUp-Token", CvamsWireMock.OK_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.runState").value("executing"));

        Approval approval = approval();
        assertThat(approval.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approval.stepUpRef()).isEqualTo(CvamsWireMock.OK_REF);
        assertThat(approval.decidedAt()).isNotNull();
        AgentRun run = run();
        assertThat(run.state()).isEqualTo(RunState.EXECUTING);
        assertThat(run.attempts().execute()).isEqualTo(1);
        assertThat(executeTasks()).singleElement().satisfies(message -> {
            assertThat(message.payload()).containsEntry("phase", "execute");
            assertThat(message.payload()).containsEntry("quoteId", "Q-1");
            assertThat(message.payload()).containsEntry("intent", "transfer.execute");
            assertThat(message.payload()).containsEntry("taskId", run.currentTaskId());
            assertThat(message.payload()).doesNotContainKey("grantToken");
        });
        assertThat(activities(ActivityType.APPROVED)).singleElement()
                .satisfies(event -> assertThat(event.message()).isEqualTo("Payday sweep was approved."));
    }

    @Test
    void denyMarksTheRunDenied() throws Exception {
        mvc.perform(post("/v1/approvals/" + APPROVAL + "/deny").header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("denied"));

        assertThat(approval().status()).isEqualTo(ApprovalStatus.DENIED);
        assertThat(run().state()).isEqualTo(RunState.DENIED);
        assertThat(activities(ActivityType.DENIED)).singleElement()
                .satisfies(event -> assertThat(event.message()).isEqualTo("Payday sweep was declined."));
        assertThat(baselines()).isEmpty();
        assertThat(executeTasks()).isEmpty();

        mvc.perform(post("/v1/approvals/" + APPROVAL + "/deny").header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void approveAfterExpiryIsConflictWhileTheApprovalStaysPending() throws Exception {
        mongoTemplate().updateFirst(
                Query.query(Criteria.where("_id").is(APPROVAL)),
                new Update().set("expiresAt", clock.instant().minusSeconds(60)),
                Approval.class);
        CvamsWireMock.server().resetRequests();

        mvc.perform(post("/v1/approvals/" + APPROVAL + "/approve")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header("X-StepUp-Token", CvamsWireMock.OK_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("approval_not_pending"))
                .andExpect(jsonPath("$.title").value("Approval not pending"));

        CvamsWireMock.server().verify(0, postRequestedFor(urlEqualTo("/step-up/verify")));
        assertThat(approval().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(executeTasks()).isEmpty();
    }

    @Test
    void doubleApproveSendsOneExecuteTask() throws Exception {
        CvamsWireMock.delaySuccess(400);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<MvcResult> call = () -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return mvc.perform(post("/v1/approvals/" + APPROVAL + "/approve")
                                .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                                .header("X-StepUp-Token", CvamsWireMock.OK_TOKEN))
                        .andReturn();
            };
            Future<MvcResult> first = pool.submit(call);
            Future<MvcResult> second = pool.submit(call);
            start.countDown();
            List<MvcResult> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(200, 409);
            assertThat(results).anySatisfy(result -> assertThat(result.getResponse().getContentAsString()).contains("approved"));
            assertThat(results).anySatisfy(result -> assertThat(result.getResponse().getContentAsString()).contains("approval_not_pending"));
        } finally {
            pool.shutdownNow();
            CvamsWireMock.stubHappy();
        }

        assertThat(approval().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(run().state()).isEqualTo(RunState.EXECUTING);
        assertThat(run().attempts().execute()).isEqualTo(1);
        assertThat(executeTasks()).hasSize(1);
        assertThat(activities(ActivityType.APPROVED)).hasSize(1);
    }

    @Test
    void previewChangedRejectsApprovalAndStaysPending() throws Exception {
        mongoTemplate().updateFirst(
                Query.query(Criteria.where("_id").is("run_1")),
                new Update().set("preview.effect", "SECRET_PREVIEW_TOKEN"),
                AgentRun.class);
        ListAppender<ILoggingEvent> logs = capture("com.wf.fargo.automation.approvals.ApprovalService");

        mvc.perform(post("/v1/approvals/" + APPROVAL + "/approve")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header("X-StepUp-Token", CvamsWireMock.OK_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("preview_changed"))
                .andExpect(jsonPath("$.title").value("Preview changed"));

        assertThat(approval().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(approval().decidedAt()).isNull();
        assertThat(executeTasks()).isEmpty();
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("security=true")
                && event.getFormattedMessage().contains("preview_changed"));
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("SECRET_PREVIEW_TOKEN")
                || event.getFormattedMessage().contains(CvamsWireMock.OK_TOKEN));
    }

    @Test
    void stepUpFailureLeavesTheApprovalPending() throws Exception {
        CvamsWireMock.server().resetRequests();
        mvc.perform(post("/v1/approvals/" + APPROVAL + "/approve")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header("X-StepUp-Token", "not-a-step-up"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("step_up_required"))
                .andExpect(jsonPath("$.title").value("Step-up required"));

        CvamsWireMock.server().verify(1, postRequestedFor(urlEqualTo("/step-up/verify")));
        assertThat(approval().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(executeTasks()).isEmpty();
    }

    @Test
    void cvamsDownLeavesTheApprovalPending() throws Exception {
        CvamsWireMock.stubUnavailable();
        try {
            mvc.perform(post("/v1/approvals/" + APPROVAL + "/approve")
                            .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                            .header("X-StepUp-Token", CvamsWireMock.OK_TOKEN))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("step_up_unavailable"))
                    .andExpect(jsonPath("$.title").value("Step-up unavailable"));
            CvamsWireMock.server().verify(3, postRequestedFor(urlEqualTo("/step-up/verify")));
        } finally {
            CvamsWireMock.stubHappy();
        }
        assertThat(approval().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(run().state()).isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(executeTasks()).isEmpty();
    }

    private void seed() {
        TemplateAction action = new TemplateAction("transfer", "transfer.preview", "transfer.execute");
        mongoTemplate().insert(new AgentTemplate(
                "payday_sweep:1",
                "payday_sweep",
                1,
                "Payday sweep",
                "Move extra payday money",
                new TemplateTrigger("payroll_credit"),
                action,
                AgentMode.ASK,
                Map.of(),
                new TemplateLimits(new BigDecimal("10000"), null, null),
                List.of(action.previewIntent(), action.executeIntent()),
                Duration.ofHours(24),
                "Move payday money above the amount you set.",
                "approved",
                "reviewer",
                SEEDED,
                SEEDED,
                SEEDED,
                1));
        mongoTemplate().insert(new PersonalAgent(
                "agt_1",
                CUSTOMER,
                "payday_sweep",
                1,
                "Payday sweep",
                Map.of("amount", 5000),
                new AgentTrigger("payroll_credit", List.of(), Map.of(), null),
                null,
                null,
                AgentMode.ASK,
                AgentStatus.ON,
                "grt_1",
                null,
                null,
                0L,
                SEEDED,
                SEEDED,
                1));
        RunPreview preview = new RunPreview("Q-1", "Move the extra payday money.", List.of(), Map.of());
        mongoTemplate().insert(new AgentRun(
                "run_1",
                "agt_1",
                CUSTOMER,
                "2026-10-15",
                "agt_1:2026-10-15",
                "post_1",
                Map.of("amount", 1000),
                RunState.AWAITING_APPROVAL,
                List.of(),
                preview,
                null,
                null,
                "tsk_preview",
                null,
                new RunAttempts(1, 0),
                SEEDED,
                SEEDED,
                1));
        AgentRun stored = run();
        mongoTemplate().insert(new Approval(
                APPROVAL,
                stored.runId(),
                stored.agentId(),
                stored.customerId(),
                Hashing.sha256Prefixed(CanonicalJson.of(objectMapper.valueToTree(stored.preview()))),
                ApprovalStatus.PENDING,
                clock.instant().plus(Duration.ofHours(24)),
                null,
                null,
                null,
                SEEDED,
                SEEDED,
                1));
    }

    private ListAppender<ILoggingEvent> capture(String name) {
        Logger logger = (Logger) LoggerFactory.getLogger(name);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private Approval approval() {
        Approval approval = mongoTemplate().findById(APPROVAL, Approval.class);
        assertThat(approval).isNotNull();
        return approval;
    }

    private AgentRun run() {
        AgentRun run = mongoTemplate().findById("run_1", AgentRun.class);
        assertThat(run).isNotNull();
        return run;
    }

    private List<OutboxMessage> executeTasks() {
        return mongoTemplate().find(
                        Query.query(Criteria.where("destination").in(
                                OutboxDestination.ORCHESTRATOR_TASK, OutboxDestination.A2A_TASK)),
                        OutboxMessage.class)
                .stream()
                .filter(message -> "execute".equals(message.payload().get("phase")))
                .toList();
    }

    private List<OutboxMessage> baselines() {
        return mongoTemplate().find(
                Query.query(Criteria.where("destination").is(OutboxDestination.BASELINE_UPDATES)), OutboxMessage.class);
    }

    private List<AgentActivity> activities(ActivityType type) {
        return mongoTemplate().find(Query.query(Criteria.where("type").is(type)), AgentActivity.class);
    }
}
