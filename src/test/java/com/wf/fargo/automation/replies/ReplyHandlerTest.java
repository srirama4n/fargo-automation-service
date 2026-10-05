package com.wf.fargo.automation.replies;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.wf.fargo.automation.domain.Grant;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.RunAttempts;
import com.wf.fargo.automation.domain.RunPreview;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.domain.TemplateAction;
import com.wf.fargo.automation.domain.TemplateLimits;
import com.wf.fargo.automation.domain.TemplateTrigger;
import com.wf.fargo.automation.store.IndexInitializer;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

class ReplyHandlerTest extends EmbeddedMongoTest {

    private static final Instant SEEDED = Instant.parse("2026-10-01T00:00:00Z");
    private static final String TASK = "tsk_preview";

    @Autowired
    private ReplyHandler handler;

    @Autowired
    private IndexInitializer indexInitializer;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void indexes() {
        indexInitializer.ensureIndexes();
    }

    @Test
    void askPreviewRequestsApprovalAndPushesADeepLink() {
        seed(AgentMode.ASK, new TemplateAction("transfer", "transfer.preview", "transfer.execute"), "Payday sweep",
                Map.of("merchantName", "StreamFlix", "oldAmount", "15.49", "newAmount", "17.99", "cardDisplay", "Debit ••9034"));
        Instant before = Instant.now();
        handler.handle(ok("preview", quote("Q-1", "Future charges will be declined"), null));

        AgentRun run = run();
        assertThat(run.state()).isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(run.preview().quoteId()).isEqualTo("Q-1");
        Approval approval = mongoTemplate().findOne(Query.query(Criteria.where("runId").is(run.runId())), Approval.class);
        assertThat(approval).isNotNull();
        assertThat(approval.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(approval.previewHash()).isEqualTo(hash(run.preview()));
        assertThat(approval.expiresAt()).isAfter(before.plus(Duration.ofHours(24)).minusSeconds(5));
        assertThat(approval.expiresAt()).isBefore(before.plus(Duration.ofHours(24)).plusSeconds(30));
        assertThat(activities(ActivityType.APPROVAL_REQUESTED)).singleElement()
                .satisfies(event -> assertThat(event.message()).isEqualTo(
                        "StreamFlix went from $15.49 to $17.99. Block future StreamFlix charges on Debit ••9034?"));
        Map<String, Object> push = onlyPush();
        assertThat(push).containsEntry("category", "agent_approval");
        assertThat(push).containsEntry("deepLink", "wf://fargo/approvals/" + approval.approvalId());
        assertThat(push).containsEntry("collapseKey", run.runId());
        assertThat(push).containsEntry("body", "StreamFlix went from $15.49 to $17.99. Block future StreamFlix charges on Debit ••9034?");
        assertThat(tasks()).isEmpty();
    }

    @Test
    void actThenTellEndsDoneWithACompletionPushAndUndoLink() {
        seed(AgentMode.ACT_THEN_TELL, new TemplateAction("card.lock", "card.lock.preview", "card.lock.execute"),
                "Card lock", Map.of());
        handler.handle(ok("preview", quote("Q-1", "The card will be locked."), null));

        AgentRun executing = run();
        assertThat(executing.state()).isEqualTo(RunState.EXECUTING);
        assertThat(executing.attempts().execute()).isEqualTo(1);
        assertThat(executing.currentTaskId()).isNotEqualTo(TASK);
        assertThat(tasks()).singleElement().satisfies(message -> {
            assertThat(message.payload()).containsEntry("phase", "execute");
            assertThat(message.payload()).containsEntry("quoteId", "Q-1");
            assertThat(message.payload()).containsEntry("intent", "card.lock.execute");
            assertThat(message.payload()).containsEntry("taskId", executing.currentTaskId());
            assertThat(message.payload()).doesNotContainKey("grantToken");
        });
        assertThat(mongoTemplate().findAll(Approval.class)).isEmpty();

        handler.handle(ok("execute", null, Map.of("lockId", "lock_1")));

        AgentRun done = run();
        assertThat(done.state()).isEqualTo(RunState.DONE);
        assertThat(done.outcome()).containsEntry("lockId", "lock_1");
        assertThat(activities(ActivityType.COMPLETED)).isNotEmpty();
        Map<String, Object> push = onlyPush();
        assertThat(push).containsEntry("category", "agent_update");
        assertThat(push).containsEntry("body", "Card lock is done.");
        assertThat(push).containsEntry("deepLink", "wf://fargo/cards/unlock/" + done.runId());
        assertThat(push).containsEntry("collapseKey", done.runId());
    }

    @Test
    void readModeEndsDoneAndPushesTheResult() {
        seed(AgentMode.READ, new TemplateAction("bill.read", "bill.read", "bill.read"), "Weekly bill check", Map.of());
        handler.handle(ok("preview", quote("Q-2", ReplySteps.READ_EFFECT), null));

        AgentRun run = run();
        assertThat(run.state()).isEqualTo(RunState.DONE);
        assertThat(run.preview().effect()).isEqualTo(ReplySteps.READ_EFFECT);
        assertThat(activities(ActivityType.NOTIFIED)).isNotEmpty();
        assertThat(onlyPush()).containsEntry("body", ReplySteps.READ_EFFECT);
        assertThat(onlyPush()).containsEntry("category", "agent_update");
        assertThat(tasks()).isEmpty();
        assertThat(mongoTemplate().findAll(Approval.class)).isEmpty();
    }

    @Test
    void retryablePreviewKeepsTheStateAndResendsWithBackoff() {
        seed(AgentMode.ASK, new TemplateAction("transfer", "transfer.preview", "transfer.execute"), "Payday sweep", Map.of());
        Instant before = Instant.now();
        handler.handle(error("preview", true, "temporary", "Try again"));

        AgentRun run = run();
        assertThat(run.state()).isEqualTo(RunState.PREVIEWING);
        assertThat(run.currentTaskId()).isNotEqualTo(TASK);
        assertThat(run.attempts().preview()).isEqualTo(2);
        assertThat(tasks()).singleElement().satisfies(message -> {
            assertThat(message.payload()).containsEntry("phase", "preview");
            assertThat(message.payload()).containsEntry("intent", "transfer.preview");
            assertThat(message.payload()).containsEntry("taskId", run.currentTaskId());
            assertThat(message.nextAttemptAt()).isAfter(before.plusSeconds(1));
        });
        assertThat(pushes()).isEmpty();
    }

    @Test
    void nonRetryablePreviewFailsAndSaysNothingChanged() {
        seed(AgentMode.ASK, new TemplateAction("transfer", "transfer.preview", "transfer.execute"), "Payday sweep", Map.of());
        handler.handle(error("preview", false, "rejected", "internal explosion"));

        AgentRun run = run();
        assertThat(run.state()).isEqualTo(RunState.FAILED);
        assertThat(run.error().code()).isEqualTo("rejected");
        assertThat(run.error().message()).isEqualTo("internal explosion");
        assertThat(activities(ActivityType.FAILED)).singleElement()
                .satisfies(event -> assertThat(event.message()).isEqualTo("We couldn't complete this. Nothing was changed."));
        assertThat(onlyPush()).containsEntry("body", "We couldn't complete this. Nothing was changed.");
        assertThat(onlyPush()).containsEntry("category", "agent_update");
        assertThat(tasks()).isEmpty();
    }

    @Test
    void retryableExecuteResendsTheSameQuoteUntilTheThirdAttemptFails() {
        seed(AgentMode.ACT_THEN_TELL, new TemplateAction("card.lock", "card.lock.preview", "card.lock.execute"),
                "Card lock", Map.of());
        mongoTemplate().updateFirst(
                Query.query(Criteria.where("_id").is("run_1")),
                new org.springframework.data.mongodb.core.query.Update()
                        .set("state", RunState.EXECUTING)
                        .set("attempts", new RunAttempts(1, 1))
                        .set("preview", new RunPreview("Q-9", "The card will be locked.", List.of(), Map.of())),
                AgentRun.class);

        handler.handle(error("execute", true, "temporary", "Try again"));
        AgentRun second = run();
        assertThat(second.state()).isEqualTo(RunState.EXECUTING);
        assertThat(second.attempts().execute()).isEqualTo(2);
        assertThat(tasks()).singleElement().satisfies(message -> {
            assertThat(message.payload()).containsEntry("phase", "execute");
            assertThat(message.payload()).containsEntry("quoteId", "Q-9");
            assertThat(message.payload()).containsEntry("taskId", second.currentTaskId());
        });

        handler.handle(error("execute", true, "temporary", "Try again"));
        assertThat(run().attempts().execute()).isEqualTo(3);
        assertThat(run().state()).isEqualTo(RunState.EXECUTING);

        handler.handle(error("execute", true, "temporary", "Try again"));
        AgentRun failed = run();
        assertThat(failed.state()).isEqualTo(RunState.FAILED);
        assertThat(failed.error().code()).isEqualTo("temporary");
        assertThat(onlyPush().get("body").toString()).doesNotContain("Nothing was changed");
        assertThat(onlyPush()).containsEntry("body", "We couldn't complete this.");
        assertThat(activities(ActivityType.FAILED)).isNotEmpty();
    }

    @Test
    void staleReplyDoesNotChangeTheRun() {
        seed(AgentMode.ASK, new TemplateAction("transfer", "transfer.preview", "transfer.execute"), "Payday sweep", Map.of());
        Map<String, Object> reply = ok("preview", quote("Q-1", "Nope"), null);
        reply.put("taskId", "tsk_other");
        handler.handle(reply);

        AgentRun run = run();
        assertThat(run.state()).isEqualTo(RunState.PREVIEWING);
        assertThat(run.currentTaskId()).isEqualTo(TASK);
        assertThat(run.preview()).isNull();
        assertThat(mongoTemplate().findAll(OutboxMessage.class)).isEmpty();
        assertThat(mongoTemplate().findAll(AgentActivity.class)).isEmpty();
    }

    private void seed(AgentMode mode, TemplateAction action, String name, Map<String, Object> context) {
        mongoTemplate().insert(new AgentTemplate(
                "tpl:1",
                "tpl",
                1,
                name,
                name,
                new TemplateTrigger("payroll_credit"),
                action,
                mode,
                Map.of(),
                new TemplateLimits(new BigDecimal("10000"), null, null),
                List.of(action.previewIntent()),
                Duration.ofHours(24),
                name,
                "approved",
                "reviewer",
                SEEDED,
                SEEDED,
                SEEDED,
                1));
        mongoTemplate().insert(new PersonalAgent(
                "agt_1",
                "cust_1",
                "tpl",
                1,
                name,
                Map.of(),
                new AgentTrigger("payroll_credit", List.of(), Map.of(), null),
                null,
                null,
                mode,
                AgentStatus.ON,
                "grt_1",
                null,
                null,
                0L,
                SEEDED,
                SEEDED,
                1));
        mongoTemplate().insert(new Grant(
                "grt_1", "agt_1", "cust_1", List.of(action.previewIntent()), List.of(), Map.of(),
                SEEDED, null, null, null, SEEDED, SEEDED, 1));
        mongoTemplate().insert(new AgentRun(
                "run_1",
                "agt_1",
                "cust_1",
                "2026-10-15",
                "agt_1:2026-10-15",
                "post_1",
                context,
                RunState.PREVIEWING,
                List.of(),
                null,
                null,
                null,
                TASK,
                null,
                new RunAttempts(1, 0),
                SEEDED,
                SEEDED,
                1));
    }

    private Map<String, Object> ok(String phase, Map<String, Object> quote, Map<String, Object> outcome) {
        Map<String, Object> body = base(phase, "ok");
        body.put("quote", quote);
        body.put("outcome", outcome);
        body.put("error", null);
        return body;
    }

    private Map<String, Object> error(String phase, boolean retryable, String code, String message) {
        Map<String, Object> body = base(phase, "error");
        body.put("quote", null);
        body.put("outcome", null);
        body.put("error", Map.of("code", code, "message", message, "retryable", retryable));
        return body;
    }

    private Map<String, Object> base(String phase, String status) {
        AgentRun run = run();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", 1);
        body.put("messageId", "m-" + phase + "-" + System.nanoTime());
        body.put("occurredAt", "2026-10-15T13:06:00Z");
        body.put("taskId", run.currentTaskId());
        body.put("runId", run.runId());
        body.put("customerId", run.customerId());
        body.put("phase", phase);
        body.put("status", status);
        return body;
    }

    private static Map<String, Object> quote(String quoteId, String effect) {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("quoteId", quoteId);
        quote.put("effect", effect);
        quote.put("warnings", List.of());
        quote.put("data", Map.of("nextExpectedCharge", "2026-10-26"));
        return quote;
    }

    private String hash(RunPreview preview) {
        return Hashing.sha256Prefixed(CanonicalJson.of(objectMapper.valueToTree(preview)));
    }

    private AgentRun run() {
        AgentRun run = mongoTemplate().findById("run_1", AgentRun.class);
        assertThat(run).isNotNull();
        return run;
    }

    private List<AgentActivity> activities(ActivityType type) {
        return mongoTemplate().find(Query.query(Criteria.where("type").is(type)), AgentActivity.class);
    }

    private List<OutboxMessage> pushes() {
        return mongoTemplate().find(
                Query.query(Criteria.where("destination").is(OutboxDestination.PUSH)), OutboxMessage.class);
    }

    private Map<String, Object> onlyPush() {
        assertThat(pushes()).hasSize(1);
        return pushes().getFirst().payload();
    }

    private List<OutboxMessage> tasks() {
        return mongoTemplate().find(
                Query.query(Criteria.where("destination").in(
                        OutboxDestination.ORCHESTRATOR_TASK, OutboxDestination.A2A_TASK)),
                OutboxMessage.class);
    }
}
