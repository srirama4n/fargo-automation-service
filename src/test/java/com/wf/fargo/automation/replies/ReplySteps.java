package com.wf.fargo.automation.replies;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.domain.ActivityType;
import com.wf.fargo.automation.domain.AgentActivity;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.Approval;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.RunState;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

public class ReplySteps {

    static final String READ_EFFECT = "Your bills this week total $42.";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private EmbeddedKafkaKraftBroker broker;

    @Autowired
    private AppProperties properties;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @When("the preview reply returns quote {string}")
    public void previewReply(String quoteId) throws Exception {
        AgentRun run = onlyRun();
        publish(reply(run, "preview", "ok", quote(quoteId, READ_EFFECT), null, null));
    }

    @When("the execute reply succeeds")
    public void executeReplySucceeds() throws Exception {
        AgentRun run = onlyRun();
        publish(reply(run, "execute", "ok", null, Map.of("lockId", "lock_1"), null));
    }

    @Then("the run is {string}")
    public void runIs(String state) {
        assertThat(onlyRun().state().name()).isEqualToIgnoringCase(state);
    }

    @Then("an execute task is sent with quote {string}")
    public void executeTask(String quoteId) {
        AgentRun run = onlyRun();
        assertThat(run.attempts().execute()).isEqualTo(1);
        List<OutboxMessage> tasks = tasks();
        assertThat(tasks).anySatisfy(message -> {
            assertThat(message.payload()).containsEntry("phase", "execute");
            assertThat(message.payload()).containsEntry("quoteId", quoteId);
            assertThat(message.payload()).containsEntry("taskId", run.currentTaskId());
            assertThat(message.payload()).doesNotContainKey("grantToken");
        });
    }

    @Then("no approval is pending")
    public void noApproval() {
        assertThat(mongoTemplate.findAll(Approval.class)).isEmpty();
    }

    @Then("activity {string} is recorded")
    public void activity(String type) {
        AgentRun run = onlyRun();
        List<AgentActivity> events = mongoTemplate.find(
                Query.query(Criteria.where("runId").is(run.runId()).and("type").is(ActivityType.valueOf(type.toUpperCase()))),
                AgentActivity.class);
        assertThat(events).isNotEmpty();
    }

    @Then("a confirmation push is sent with an undo link")
    public void confirmationPush() {
        AgentRun run = onlyRun();
        PersonalAgent agent = mongoTemplate.findById(run.agentId(), PersonalAgent.class);
        Map<String, Object> push = onlyPush();
        assertThat(push).containsEntry("category", "agent_update");
        assertThat(push).containsEntry("collapseKey", run.runId());
        assertThat(push).containsEntry("customerId", run.customerId());
        assertThat(push).containsEntry("body", agent.name() + " is done.");
        assertThat(push).containsEntry("deepLink", "wf://fargo/cards/unlock/" + run.runId());
        assertThat(run.outcome()).containsEntry("lockId", "lock_1");
    }

    @Then("a push is sent with the read result")
    public void readPush() {
        AgentRun run = onlyRun();
        Map<String, Object> push = onlyPush();
        assertThat(push).containsEntry("category", "agent_update");
        assertThat(push).containsEntry("collapseKey", run.runId());
        assertThat(push).containsEntry("body", READ_EFFECT);
        assertThat(run.preview().effect()).isEqualTo(READ_EFFECT);
        assertThat(tasks()).noneSatisfy(message -> assertThat(message.payload()).containsEntry("phase", "execute"));
    }

    private void publish(Map<String, Object> reply) throws Exception {
        AgentRun run = onlyRun();
        kafka.send(properties.topics().automationReplies(), run.customerId(), objectMapper.writeValueAsString(reply))
                .get(10, TimeUnit.SECONDS);
        awaitConsumerCaughtUp(properties.topics().automationReplies(), "automation-replies");
    }

    private Map<String, Object> reply(
            AgentRun run,
            String phase,
            String status,
            Map<String, Object> quote,
            Map<String, Object> outcome,
            Map<String, Object> error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", 1);
        body.put("messageId", "reply-" + System.nanoTime());
        body.put("occurredAt", "2026-10-15T13:06:00Z");
        body.put("taskId", run.currentTaskId());
        body.put("runId", run.runId());
        body.put("customerId", run.customerId());
        body.put("phase", phase);
        body.put("status", status);
        body.put("quote", quote);
        body.put("outcome", outcome);
        body.put("error", error);
        return body;
    }

    private static Map<String, Object> quote(String quoteId, String effect) {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("quoteId", quoteId);
        quote.put("effect", effect);
        quote.put("warnings", List.of());
        quote.put("data", Map.of());
        return quote;
    }

    private AgentRun onlyRun() {
        List<AgentRun> runs = mongoTemplate.findAll(AgentRun.class);
        assertThat(runs).hasSize(1);
        return runs.getFirst();
    }

    private Map<String, Object> onlyPush() {
        List<OutboxMessage> pushes = mongoTemplate.find(
                Query.query(Criteria.where("destination").is(OutboxDestination.PUSH)), OutboxMessage.class);
        assertThat(pushes).hasSize(1);
        return pushes.getFirst().payload();
    }

    private List<OutboxMessage> tasks() {
        return mongoTemplate.find(
                Query.query(Criteria.where("destination").in(
                        OutboxDestination.ORCHESTRATOR_TASK, OutboxDestination.A2A_TASK)),
                OutboxMessage.class);
    }

    private void awaitConsumerCaughtUp(String topic, String group) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            while (System.nanoTime() < deadline) {
                if (caughtUp(admin, topic, group)) {
                    return;
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError(topic + " consumer did not catch up");
    }

    private static boolean caughtUp(AdminClient admin, String topic, String group) throws Exception {
        Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed;
        try {
            committed = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException ex) {
            return false;
        }
        TopicPartition partition = new TopicPartition(topic, 0);
        ListOffsetsResult.ListOffsetsResultInfo end = admin.listOffsets(Map.of(partition, OffsetSpec.latest()))
                .all()
                .get(5, TimeUnit.SECONDS)
                .get(partition);
        org.apache.kafka.clients.consumer.OffsetAndMetadata offset = committed.get(partition);
        return offset != null && end != null && offset.offset() >= end.offset();
    }
}
