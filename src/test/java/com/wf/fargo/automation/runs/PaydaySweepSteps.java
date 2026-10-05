package com.wf.fargo.automation.runs;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.fargo.automation.AdjustableClock;
import com.wf.fargo.automation.approvals.CvamsWireMock;
import com.wf.fargo.automation.approvals.StepUpVerifier;
import com.wf.fargo.automation.common.CanonicalJson;
import com.wf.fargo.automation.common.Hashing;
import com.wf.fargo.automation.domain.AgentMode;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.domain.AgentTrigger;
import com.wf.fargo.automation.domain.Approval;
import com.wf.fargo.automation.domain.ApprovalStatus;
import com.wf.fargo.automation.domain.Grant;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.RunAttempts;
import com.wf.fargo.automation.domain.RunPreview;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.domain.StateHistoryEntry;
import com.wf.fargo.automation.domain.TemplateAction;
import com.wf.fargo.automation.domain.TemplateLimits;
import com.wf.fargo.automation.domain.TemplateTrigger;
import com.wf.fargo.automation.standalone.DevAuthFilter;
import com.wf.fargo.automation.store.IndexInitializer;
import com.wf.fargo.automation.sweeper.ApprovalExpiryJob;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
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
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class PaydaySweepSteps {

    private static final Instant SEEDED = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private IndexInitializer indexInitializer;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private EmbeddedKafkaKraftBroker broker;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private ApprovalExpiryJob expiryJob;

    @Autowired
    private StepUpVerifier stepUp;

    private String templateId;
    private int templateVersion;

    @Before
    public void clean() {
        clock.reset();
        stepUp.reset();
        CvamsWireMock.stubHappy();
        for (String name : mongoTemplate.getDb().listCollectionNames()) {
            if (!name.startsWith("system.")) {
                mongoTemplate.dropCollection(name);
            }
        }
        indexInitializer.ensureIndexes();
    }

    @Given("an approved template {string} version {int} with mode {string}")
    public void approvedTemplate(String templateId, int version, String mode) {
        this.templateId = templateId;
        this.templateVersion = version;
        AgentMode agentMode = AgentMode.valueOf(mode.toUpperCase());
        TemplateAction action = switch (agentMode) {
            case ACT_THEN_TELL -> new TemplateAction("card.lock", "card.lock.preview", "card.lock.execute");
            case READ -> new TemplateAction("bill.read", "bill.read", "bill.read");
            case ASK -> new TemplateAction("transfer", "transfer.preview", "transfer.execute");
        };
        String name = switch (agentMode) {
            case ACT_THEN_TELL -> "Card lock";
            case READ -> "Weekly bill check";
            case ASK -> "Payday sweep";
        };
        mongoTemplate.insert(new AgentTemplate(
                templateId + ":" + version,
                templateId,
                version,
                name,
                "Move extra payday money",
                new TemplateTrigger("payroll_credit"),
                action,
                agentMode,
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
    }

    @Given("customer {string} has an agent {string} from that template moving {int} from {string} to {string}")
    public void customerHasAgent(String customerId, String agentId, int amount, String from, String to) {
        AgentTemplate template = mongoTemplate.findOne(
                Query.query(Criteria.where("templateId").is(templateId).and("version").is(templateVersion)),
                AgentTemplate.class);
        assertThat(template).isNotNull();
        mongoTemplate.insert(new PersonalAgent(
                agentId,
                customerId,
                templateId,
                templateVersion,
                template.name(),
                Map.of("from", from, "to", to, "amount", amount),
                new AgentTrigger("payroll_credit", List.of(from), Map.of(), null),
                null,
                null,
                template.mode(),
                AgentStatus.ON,
                "grt_" + agentId,
                null,
                null,
                0L,
                SEEDED,
                SEEDED,
                1));
        mongoTemplate.insert(new Grant(
                "grt_" + agentId,
                agentId,
                customerId,
                List.of("transfer.preview", "transfer.execute"),
                List.of(from, to),
                Map.of(),
                SEEDED,
                null,
                null,
                null,
                SEEDED,
                SEEDED,
                1));
    }

    @Given("agent {string} is paused")
    public void agentIsPaused(String agentId) {
        assertThat(mongoTemplate.findById(agentId, PersonalAgent.class)).isNotNull();
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(agentId)),
                new org.springframework.data.mongodb.core.query.Update().set("status", AgentStatus.PAUSED),
                PersonalAgent.class);
    }

    @When("a trigger fires for {string} with period {string}")
    public void triggerFires(String agentId, String periodId) throws Exception {
        PersonalAgent agent = mongoTemplate.findById(agentId, PersonalAgent.class);
        assertThat(agent).isNotNull();
        String body = objectMapper.writeValueAsString(Map.of(
                "schemaVersion", 1,
                "messageId", "msg-" + periodId + "-" + System.nanoTime(),
                "occurredAt", "2026-10-15T13:05:00Z",
                "customerId", agent.customerId(),
                "agentId", agentId,
                "periodId", periodId,
                "eventRef", "post_" + periodId,
                "triggerType", "payroll_credit",
                "context", Map.of("amount", 1000.00, "currency", "USD")));
        kafka.send("trigger-fired", agent.customerId(), body).get(10, TimeUnit.SECONDS);
        awaitConsumerCaughtUp("trigger-fired");
    }

    @Then("exactly one run exists for {string}")
    public void exactlyOneRun(String agentId) {
        List<AgentRun> runs = runs(agentId);
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().state()).isEqualTo(RunState.PREVIEWING);
        assertThat(taskMessages()).hasSize(1);
    }

    @Then("the run is {string} with reason {string}")
    public void runIs(String state, String reason) {
        List<AgentRun> runs = mongoTemplate.findAll(AgentRun.class);
        assertThat(runs).hasSize(1);
        AgentRun run = runs.getFirst();
        assertThat(run.state().name()).isEqualToIgnoringCase(state);
        assertThat(run.stateHistory()).extracting(StateHistoryEntry::reason).contains(reason);
    }

    @Then("no task is sent")
    public void noTaskIsSent() {
        assertThat(taskMessages()).isEmpty();
    }

    @Then("a preview task is sent for run of {string}")
    public void previewTask(String agentId) {
        List<AgentRun> found = runs(agentId);
        assertThat(found).hasSize(1);
        assertThat(taskMessages()).anySatisfy(message -> {
            assertThat(message.payload()).containsEntry("phase", "preview");
            assertThat(message.payload()).containsEntry("agentId", agentId);
            assertThat(message.payload()).containsEntry("runId", found.getFirst().runId());
        });
    }

    @Then("an approval is pending and a push is sent")
    public void approvalPendingAndPush() {
        List<AgentRun> found = mongoTemplate.findAll(AgentRun.class);
        assertThat(found).hasSize(1);
        AgentRun run = found.getFirst();
        PersonalAgent agent = mongoTemplate.findById(run.agentId(), PersonalAgent.class);
        Approval approval = mongoTemplate.findOne(Query.query(Criteria.where("runId").is(run.runId())), Approval.class);
        assertThat(approval).isNotNull();
        assertThat(approval.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(pushes()).anySatisfy(message -> {
            assertThat(message.payload()).containsEntry("category", "agent_approval");
            assertThat(message.payload()).containsEntry("body", "Approve " + agent.name() + "?");
            assertThat(message.payload()).containsEntry("deepLink", "wf://fargo/approvals/" + approval.approvalId());
            assertThat(message.payload()).containsEntry("collapseKey", run.runId());
        });
    }

    @When("the customer approves with a valid step-up token")
    public void customerApproves() throws Exception {
        Approval approval = mongoTemplate.findOne(
                Query.query(Criteria.where("status").is(ApprovalStatus.PENDING)), Approval.class);
        assertThat(approval).isNotNull();
        mvc.perform(post("/v1/approvals/" + approval.approvalId() + "/approve")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, approval.customerId())
                        .header("X-StepUp-Token", CvamsWireMock.OK_TOKEN))
                .andExpect(status().isOk());
    }

    @Then("the run is {string} and a confirmation push is sent")
    public void doneAndConfirmation(String state) {
        List<AgentRun> found = mongoTemplate.findAll(AgentRun.class);
        assertThat(found).hasSize(1);
        AgentRun run = found.getFirst();
        assertThat(run.state().name()).isEqualToIgnoringCase(state);
        PersonalAgent agent = mongoTemplate.findById(run.agentId(), PersonalAgent.class);
        assertThat(pushes()).anySatisfy(message -> {
            assertThat(message.payload()).containsEntry("category", "agent_update");
            assertThat(message.payload()).containsEntry("body", agent.name() + " is done.");
            assertThat(message.payload()).containsEntry("collapseKey", run.runId());
            assertThat(message.payload()).doesNotContainKey("deepLink");
        });
    }

    @When("25 hours pass")
    public void hoursPass() {
        clock.advance(Duration.ofHours(25));
        expiryJob.expireDue();
    }

    @Then("the approval is {string} and the run is {string}")
    public void approvalAndRun(String approvalStatus, String runState) {
        List<Approval> approvals = mongoTemplate.findAll(Approval.class);
        assertThat(approvals).hasSize(1);
        assertThat(approvals.getFirst().status().name()).isEqualToIgnoringCase(approvalStatus);
        List<AgentRun> found = mongoTemplate.findAll(AgentRun.class);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().state().name()).isEqualToIgnoringCase(runState);
    }

    @Then("no execute task is sent")
    public void noExecuteTask() {
        assertThat(taskMessages()).noneMatch(message -> "execute".equals(message.payload().get("phase")));
    }

    @Given("a pending approval for {string}")
    public void pendingApproval(String agentId) {
        PersonalAgent agent = mongoTemplate.findById(agentId, PersonalAgent.class);
        assertThat(agent).isNotNull();
        String runId = "run_pending_" + agentId;
        mongoTemplate.insert(new AgentRun(
                runId,
                agentId,
                agent.customerId(),
                "pending",
                agentId + ":pending",
                "post_pending",
                Map.of(),
                RunState.AWAITING_APPROVAL,
                List.of(),
                new RunPreview("Q-1", "Move the extra payday money.", List.of(), Map.of()),
                null,
                null,
                "tsk_seed",
                null,
                new RunAttempts(1, 0),
                SEEDED,
                SEEDED,
                1));
        AgentRun stored = mongoTemplate.findById(runId, AgentRun.class);
        assertThat(stored).isNotNull();
        mongoTemplate.insert(new Approval(
                "apr_" + agentId,
                runId,
                agentId,
                agent.customerId(),
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

    @When("the customer approves twice at the same time")
    public void approveTwice() throws Exception {
        Approval approval = mongoTemplate.findAll(Approval.class).getFirst();
        CvamsWireMock.delaySuccess(400);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Integer> call = () -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return mvc.perform(post("/v1/approvals/" + approval.approvalId() + "/approve")
                                .header(DevAuthFilter.CUSTOMER_ID_HEADER, approval.customerId())
                                .header("X-StepUp-Token", CvamsWireMock.OK_TOKEN))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            };
            Future<Integer> first = pool.submit(call);
            Future<Integer> second = pool.submit(call);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
            CvamsWireMock.stubHappy();
        }
    }

    @Then("exactly one execute task is sent")
    public void exactlyOneExecute() {
        assertThat(taskMessages().stream()
                        .filter(message -> "execute".equals(message.payload().get("phase")))
                        .toList())
                .hasSize(1);
    }

    public void awaitTrigger() throws Exception {
        awaitConsumerCaughtUp("trigger-fired");
    }

    private List<OutboxMessage> taskMessages() {
        return mongoTemplate.find(
                Query.query(Criteria.where("destination").in(
                        OutboxDestination.ORCHESTRATOR_TASK, OutboxDestination.A2A_TASK)),
                OutboxMessage.class);
    }

    private List<OutboxMessage> pushes() {
        return mongoTemplate.find(
                Query.query(Criteria.where("destination").is(OutboxDestination.PUSH)), OutboxMessage.class);
    }

    private List<AgentRun> runs(String agentId) {
        return mongoTemplate.find(Query.query(Criteria.where("agentId").is(agentId)), AgentRun.class);
    }

    private void awaitConsumerCaughtUp(String topic) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            while (System.nanoTime() < deadline) {
                if (caughtUp(admin, topic)) {
                    return;
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("trigger-fired consumer did not catch up");
    }

    private static boolean caughtUp(AdminClient admin, String topic) throws Exception {
        Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed;
        try {
            committed = admin.listConsumerGroupOffsets("automation-service")
                    .partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException ex) {
            return false;
        }
        Map<TopicPartition, OffsetSpec> request = Map.of(new TopicPartition(topic, 0), OffsetSpec.latest());
        ListOffsetsResult.ListOffsetsResultInfo end = admin.listOffsets(request).all().get(5, TimeUnit.SECONDS)
                .get(new TopicPartition(topic, 0));
        org.apache.kafka.clients.consumer.OffsetAndMetadata offset = committed.get(new TopicPartition(topic, 0));
        return offset != null && end != null && offset.offset() >= end.offset();
    }
}
