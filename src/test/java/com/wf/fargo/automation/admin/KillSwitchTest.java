package com.wf.fargo.automation.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.domain.ActivityType;
import com.wf.fargo.automation.domain.AgentActivity;
import com.wf.fargo.automation.domain.AgentMode;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.AgentTrigger;
import com.wf.fargo.automation.domain.Approval;
import com.wf.fargo.automation.domain.ApprovalStatus;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.RunAttempts;
import com.wf.fargo.automation.domain.RunPreview;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.standalone.DevAuthFilter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class KillSwitchTest extends EmbeddedMongoTest {

    private static final Instant SEEDED = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = capture(KillSwitchService.class.getName());
    }

    @AfterEach
    void detachLogs() {
        ((Logger) LoggerFactory.getLogger(KillSwitchService.class)).detachAppender(logs);
    }

    @Test
    void pauseAllRequiresAnAdmin() throws Exception {
        mvc.perform(post("/v1/admin/agents/pause-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"incident INC123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
        mvc.perform(post("/v1/admin/agents/pause-all")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"incident INC123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pauseAllPausesOneTemplateAndLeavesPendingApprovals() throws Exception {
        agent("agt_on_1", "cust_1", "payday_sweep", AgentStatus.ON);
        agent("agt_on_2", "cust_2", "payday_sweep", AgentStatus.ON);
        agent("agt_other", "cust_3", "other_tpl", AgentStatus.ON);
        agent("agt_paused", "cust_4", "payday_sweep", AgentStatus.PAUSED);
        agent("agt_deleted", "cust_5", "payday_sweep", AgentStatus.DELETED);
        approval("agt_on_1", "cust_1");

        MvcResult result = mvc.perform(post("/v1/admin/agents/pause-all")
                        .header(DevAuthFilter.CALLER_HEADER, "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"payday_sweep\",\"reason\":\"incident INC123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(2))
                .andExpect(jsonPath("$.batches").value(1))
                .andExpect(jsonPath("$.status").value("completed"))
                .andReturn();
        String jobId = JsonPath.read(result.getResponse().getContentAsString(), "$.jobId");

        mvc.perform(get("/v1/admin/jobs/" + jobId).header(DevAuthFilter.CALLER_HEADER, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.paused").value(2))
                .andExpect(jsonPath("$.status").value("completed"));
        mvc.perform(get("/v1/admin/jobs/job_missing").header(DevAuthFilter.CALLER_HEADER, "admin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));

        assertThat(statusOf("agt_on_1")).isEqualTo(AgentStatus.PAUSED);
        assertThat(statusOf("agt_on_2")).isEqualTo(AgentStatus.PAUSED);
        assertThat(statusOf("agt_other")).isEqualTo(AgentStatus.ON);
        assertThat(statusOf("agt_paused")).isEqualTo(AgentStatus.PAUSED);
        assertThat(statusOf("agt_deleted")).isEqualTo(AgentStatus.DELETED);
        assertThat(removes()).hasSize(2);
        assertThat(removes()).allSatisfy(message -> {
            assertThat(message.payload()).containsEntry("op", "remove");
            assertThat(message.payload()).containsEntry("schemaVersion", 1);
            assertThat(message.payload()).doesNotContainKey("backfill");
        });
        assertThat(mongoTemplate().findById("apr_agt_on_1", Approval.class).status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(mongoTemplate().findById("run_agt_on_1", AgentRun.class).state()).isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(pausedActivities()).hasSize(2);
        assertThat(pausedActivities()).allSatisfy(event -> {
            assertThat(event.message()).isEqualTo("Payday is paused.");
            assertThat(event.data()).containsEntry("admin", "admin");
            assertThat(event.data()).containsEntry("reason", "incident INC123");
        });
        assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().contains("admin=admin")
                && event.getFormattedMessage().contains("incident INC123"));
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("cust_"));
    }

    @Test
    void expirePendingNotifiesAndExpiresApprovalsWithKillSwitchReason() throws Exception {
        agent("agt_on_1", "cust_1", "payday_sweep", AgentStatus.ON);
        approval("agt_on_1", "cust_1");

        mvc.perform(post("/v1/admin/agents/pause-all")
                        .header(DevAuthFilter.CALLER_HEADER, "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"payday_sweep\",\"reason\":\"incident INC123\",\"expirePending\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(1));

        assertThat(mongoTemplate().findById("apr_agt_on_1", Approval.class).status()).isEqualTo(ApprovalStatus.EXPIRED);
        AgentRun run = mongoTemplate().findById("run_agt_on_1", AgentRun.class);
        assertThat(run.state()).isEqualTo(RunState.EXPIRED);
        assertThat(run.stateHistory()).anySatisfy(entry -> assertThat(entry.reason()).isEqualTo("kill_switch"));
        assertThat(mongoTemplate().findAll(OutboxMessage.class).stream()
                        .filter(message -> message.destination() == OutboxDestination.PUSH)
                        .toList())
                .singleElement()
                .satisfies(message -> assertThat(message.payload())
                        .containsEntry("body", "This request expired, nothing was changed."));
        assertThat(mongoTemplate().findAll(AgentActivity.class)).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo(ActivityType.EXPIRED);
            assertThat(event.message()).isEqualTo("This request expired, nothing was changed.");
        });
    }

    @Test
    void fiveHundredOneAgentsUseTwoBatches() throws Exception {
        insertAgents(501, "batch_tpl");

        mvc.perform(post("/v1/admin/agents/pause-all")
                        .header(DevAuthFilter.CALLER_HEADER, "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"batch_tpl\",\"reason\":\"incident INC123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(501))
                .andExpect(jsonPath("$.batches").value(2))
                .andExpect(jsonPath("$.status").value("completed"));

        assertThat(removes()).hasSize(501);
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void tenThousandAgentsArePausedInBatchesOfFiveHundred() throws Exception {
        insertAgents(10_000, "bulk_pause");

        mvc.perform(post("/v1/admin/agents/pause-all")
                        .header(DevAuthFilter.CALLER_HEADER, "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"bulk_pause\",\"reason\":\"incident INC123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(10_000))
                .andExpect(jsonPath("$.batches").value(20))
                .andExpect(jsonPath("$.status").value("completed"));

        assertThat(mongoTemplate().count(
                        Query.query(Criteria.where("templateId").is("bulk_pause").and("status").is(AgentStatus.PAUSED)),
                        PersonalAgent.class))
                .isEqualTo(10_000);
        assertThat(mongoTemplate().count(
                        Query.query(Criteria.where("destination")
                                .is(OutboxDestination.AGENT_CONTROL)
                                .and("payload.op")
                                .is("remove")),
                        OutboxMessage.class))
                .isEqualTo(10_000);
    }

    private void insertAgents(int count, String templateId) {
        List<PersonalAgent> batch = new ArrayList<>(1000);
        for (int i = 0; i < count; i++) {
            batch.add(agentDoc("agt_" + templateId + "_" + i, "c" + i, templateId, AgentStatus.ON));
            if (batch.size() == 1000) {
                mongoTemplate().insert(batch, PersonalAgent.class);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            mongoTemplate().insert(batch, PersonalAgent.class);
        }
    }

    private void agent(String agentId, String customerId, String templateId, AgentStatus status) {
        mongoTemplate().insert(agentDoc(agentId, customerId, templateId, status));
    }

    private PersonalAgent agentDoc(String agentId, String customerId, String templateId, AgentStatus status) {
        return new PersonalAgent(
                agentId,
                customerId,
                templateId,
                1,
                "Payday",
                Map.of(),
                new AgentTrigger("payroll_credit", List.of(), Map.of(), null),
                null,
                null,
                AgentMode.ASK,
                status,
                "grt_" + agentId,
                null,
                null,
                0L,
                SEEDED,
                SEEDED,
                1);
    }

    private void approval(String agentId, String customerId) {
        mongoTemplate().insert(new AgentRun(
                "run_" + agentId,
                agentId,
                customerId,
                "2026-10-15",
                agentId + ":2026-10-15",
                "post_" + agentId,
                Map.of(),
                RunState.AWAITING_APPROVAL,
                List.of(),
                new RunPreview("Q-1", "Move money", List.of(), Map.of()),
                null,
                null,
                "tsk_" + agentId,
                null,
                new RunAttempts(1, 0),
                SEEDED,
                SEEDED,
                1));
        mongoTemplate().insert(new Approval(
                "apr_" + agentId,
                "run_" + agentId,
                agentId,
                customerId,
                "hash",
                ApprovalStatus.PENDING,
                SEEDED.plusSeconds(86_400),
                null,
                null,
                null,
                SEEDED,
                SEEDED,
                1));
    }

    private AgentStatus statusOf(String agentId) {
        return mongoTemplate().findById(agentId, PersonalAgent.class).status();
    }

    private List<OutboxMessage> removes() {
        return mongoTemplate().find(
                Query.query(Criteria.where("destination").is(OutboxDestination.AGENT_CONTROL)), OutboxMessage.class);
    }

    private List<AgentActivity> pausedActivities() {
        return mongoTemplate().find(Query.query(Criteria.where("type").is(ActivityType.PAUSED)), AgentActivity.class);
    }

    private static ListAppender<ILoggingEvent> capture(String name) {
        Logger logger = (Logger) LoggerFactory.getLogger(name);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
