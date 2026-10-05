package com.wf.fargo.automation.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.domain.StateHistoryEntry;
import com.wf.fargo.automation.outbox.OutboxDeliverer;
import com.wf.fargo.automation.replies.ReplyHandler;
import com.wf.fargo.automation.runs.RunService;
import com.wf.fargo.automation.runs.TriggerFired;
import com.wf.fargo.automation.runs.TriggerFixtures;
import com.wf.fargo.automation.store.IndexInitializer;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class A2aDispatchTest extends EmbeddedMongoTest {

    private static WireMockServer wireMock;

    @DynamicPropertySource
    static void a2a(DynamicPropertyRegistry registry) {
        if (wireMock == null) {
            wireMock = new WireMockServer(wireMockConfig().dynamicPort());
            wireMock.start();
        }
        registry.add("automation.dispatch.mode", () -> "a2a");
        registry.add("automation.a2a.supervisor-url", wireMock::baseUrl);
        registry.add("automation.a2a.timeout", () -> "PT2S");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Autowired
    private RunService runs;

    @Autowired
    private OutboxDeliverer deliverer;

    @Autowired
    private IndexInitializer indexInitializer;

    @MockBean
    private ReplyHandler replyHandler;

    @BeforeEach
    void indexes() {
        indexInitializer.ensureIndexes();
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo("/")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"ok\",\"phase\":\"preview\",\"taskId\":\"tsk_wm\",\"runId\":\"run_wm\",\"customerId\":\"cust_1\"}")));
    }

    @Test
    void duplicateAndPausedScenariosUseA2aAndWireMockSeesThePreviewTask() {
        TriggerFixtures.seed(mongoTemplate(), "agt_1", AgentStatus.ON, null);
        TriggerFired trigger = TriggerFixtures.fired("agt_1", "2026-10-15");
        runs.start(trigger);
        runs.start(trigger);

        List<AgentRun> stored = mongoTemplate().find(Query.query(Criteria.where("agentId").is("agt_1")), AgentRun.class);
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().state()).isEqualTo(RunState.PREVIEWING);
        List<OutboxMessage> tasks = tasks();
        assertThat(tasks).singleElement().satisfies(message -> {
            assertThat(message.destination()).isEqualTo(OutboxDestination.A2A_TASK);
            assertThat(message.payload()).doesNotContainKey("grantToken");
        });

        deliverer.deliver(tasks.getFirst());

        wireMock.verify(postRequestedFor(urlEqualTo("/"))
                .withHeader("Content-Type", equalTo("application/json")));
        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo("/"))).getFirst().getBodyAsString())
                .contains("\"phase\":\"preview\"")
                .contains("\"intent\":\"transfer.preview\"")
                .contains("grantToken");
        verify(replyHandler).handle(anyMap());
        OutboxMessage storedAfter = mongoTemplate().findById(tasks.getFirst().messageId(), OutboxMessage.class);
        assertThat(storedAfter.payload()).doesNotContainKey("grantToken");

        reset(replyHandler);
        mongoTemplate().dropCollection("agent_runs");
        mongoTemplate().dropCollection("outbox");
        assertThat(mongoTemplate().findById("agt_1", PersonalAgent.class)).isNotNull();
        mongoTemplate().updateFirst(
                Query.query(Criteria.where("_id").is("agt_1")),
                new org.springframework.data.mongodb.core.query.Update().set("status", AgentStatus.PAUSED),
                PersonalAgent.class);
        wireMock.resetRequests();
        runs.start(TriggerFixtures.fired("agt_1", "2026-10-16"));
        AgentRun skipped = mongoTemplate().findOne(Query.query(Criteria.where("periodId").is("2026-10-16")), AgentRun.class);
        assertThat(skipped.state()).isEqualTo(RunState.SKIPPED);
        assertThat(skipped.stateHistory()).extracting(StateHistoryEntry::reason).contains("agent_paused");
        assertThat(tasks()).isEmpty();
        wireMock.verify(0, postRequestedFor(urlEqualTo("/")));
        verifyNoInteractions(replyHandler);
    }

    private List<OutboxMessage> tasks() {
        return mongoTemplate().find(
                Query.query(Criteria.where("destination").in(
                        OutboxDestination.ORCHESTRATOR_TASK, OutboxDestination.A2A_TASK)),
                OutboxMessage.class);
    }
}
