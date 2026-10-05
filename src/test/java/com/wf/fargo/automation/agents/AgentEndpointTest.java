package com.wf.fargo.automation.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.domain.AgentMode;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.domain.AgentTrigger;
import com.wf.fargo.automation.domain.Grant;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.TemplateAction;
import com.wf.fargo.automation.domain.TemplateLimits;
import com.wf.fargo.automation.domain.TemplateTrigger;
import com.wf.fargo.automation.domain.TriggerSchedule;
import com.wf.fargo.automation.standalone.DevAuthFilter;
import com.wf.fargo.automation.store.IndexInitializer;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class AgentEndpointTest extends EmbeddedMongoTest {

    private static final String CUSTOMER = "cust_1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IndexInitializer indexInitializer;

    @BeforeEach
    void indexes() {
        indexInitializer.ensureIndexes();
        insertTemplate(payday());
        insertTemplate(draftTemplate());
    }

    @Test
    void confirmationReturnsAToken() throws Exception {
        mvc.perform(post("/v1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .content(confirmationBody(params(5000))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.confirmationToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void listTemplatesReturnsApprovedOnly() throws Exception {
        mvc.perform(get("/v1/templates")
                        .param("status", "approved")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates.length()").value(1))
                .andExpect(jsonPath("$.templates[0].templateId").value("payday_sweep"))
                .andExpect(jsonPath("$.templates[0].version").value(1))
                .andExpect(jsonPath("$.templates[0].mode").value("ask"))
                .andExpect(jsonPath("$.templates[0].effectText").isNotEmpty())
                .andExpect(jsonPath("$.templates[0].paramSchema").isMap())
                .andExpect(jsonPath("$.templates[0].limits.maxAmount").exists());
    }

    @Test
    void validateRejectsAmountOverTheLimit() throws Exception {
        mvc.perform(post("/v1/agents/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .content(confirmationBody(params(10001))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].field").value("params.amount"))
                .andExpect(jsonPath("$.errors[0].code").value("over_limit"));
    }

    @Test
    void validateAcceptsADraftAndReturnsItsHash() throws Exception {
        mvc.perform(post("/v1/agents/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .content(confirmationBody(params(5000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.draftHash").value(org.hamcrest.Matchers.startsWith("sha256:")))
                .andExpect(jsonPath("$.effectText").value("Move payday money above the amount you set."))
                .andExpect(jsonPath("$.errors.length()").value(0));
    }

    @Test
    void createEmitsExactlyOneAgentControlUpsertAndIgnoresExtraTools() throws Exception {
        String token = confirmationToken(params(5000));
        MvcResult created = mvc.perform(post("/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .content(createBody(params(5000), token, List.of("transfer.execute_external"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("on"))
                .andExpect(jsonPath("$.agentId").value(org.hamcrest.Matchers.startsWith("agt_")))
                .andExpect(jsonPath("$.grantId").value(org.hamcrest.Matchers.startsWith("grt_")))
                .andReturn();
        String grantId = objectMapper.readTree(created.getResponse().getContentAsString()).get("grantId").asText();
        Grant grant = mongoTemplate().findById(grantId, Grant.class);
        assertThat(grant.allowedTools()).containsExactly("transfer.preview", "transfer.execute");
        List<OutboxMessage> upserts = agentControl("upsert");
        assertThat(upserts).hasSize(1);
        assertThat(upserts.get(0).key()).isEqualTo(CUSTOMER);
        assertThat(upserts.get(0).payload()).containsEntry("backfill", true);
        assertThat(upserts.get(0).payload()).containsEntry("op", "upsert");
    }

    @Test
    void createRejectsAMismatchedConfirmation() throws Exception {
        String token = confirmationToken(params(5000));
        mvc.perform(post("/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .content(createBody(params(6000), token, List.of())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("confirmation_invalid"));
        assertThat(agentControl("upsert")).isEmpty();
    }

    @Test
    void reusedConfirmationIsRejected() throws Exception {
        Map<String, Object> params = params(4000);
        String token = confirmationToken(params);
        String body = createBody(params, token, List.of());
        mvc.perform(post("/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("confirmation_invalid"));
    }

    @Test
    void createRejectsWhenTheAgentLimitIsReached() throws Exception {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        for (int i = 0; i < 20; i++) {
            mongoTemplate().insert(new PersonalAgent(
                    "agt_existing_" + i,
                    CUSTOMER,
                    "other",
                    1,
                    "Existing",
                    Map.of("n", i),
                    new AgentTrigger("payroll_credit", List.of(), Map.of(), null),
                    null,
                    null,
                    AgentMode.ASK,
                    AgentStatus.ON,
                    "grt_existing_" + i,
                    null,
                    null,
                    0L,
                    now,
                    now,
                    1));
        }
        String token = confirmationToken(params(5000));
        mvc.perform(post("/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .content(createBody(params(5000), token, List.of())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("agent_limit_reached"));
        assertThat(agentControl("upsert")).isEmpty();
    }

    @Test
    void duplicateAgentIsAPolicyViolation() throws Exception {
        Map<String, Object> params = params(2500);
        String first = confirmationToken(params);
        mvc.perform(post("/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .content(createBody(params, first, List.of())))
                .andExpect(status().isCreated());
        String second = confirmationToken(params);
        mvc.perform(post("/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .content(createBody(params, second, List.of())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("policy_violation"))
                .andExpect(jsonPath("$.errors[?(@.code == 'duplicate_agent')]").isNotEmpty());
    }

    @Test
    void listAndDetailStayWithTheOwningCustomer() throws Exception {
        String agentId = createdAgentId(params(1500));
        mvc.perform(get("/v1/customers/" + CUSTOMER + "/agents")
                        .param("status", "on,paused")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents[0].agentId").value(agentId))
                .andExpect(jsonPath("$.agents[0].mode").value("ask"))
                .andExpect(jsonPath("$.agents[0].status").value("on"))
                .andExpect(jsonPath("$.agents[0].effectText").isNotEmpty());
        mvc.perform(get("/v1/customers/" + CUSTOMER + "/agents")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_other"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        mvc.perform(get("/v1/agents/" + agentId)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.grant.allowedTools[0]").value("transfer.preview"))
                .andExpect(jsonPath("$.grant.accountScopes").isArray())
                .andExpect(jsonPath("$.runs").isArray())
                .andExpect(jsonPath("$.params.amount").value(1500));
        mvc.perform(get("/v1/agents/" + agentId)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_other"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pauseAndResumePublishControlMessages() throws Exception {
        String agentId = createdAgentId(params(1800));
        String etag = etag(agentId);
        mvc.perform(patch("/v1/agents/" + agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header("If-Match", etag)
                        .content("{\"status\":\"paused\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paused"));
        assertThat(agentControl("remove")).hasSize(1);
        String pausedEtag = etag(agentId);
        mvc.perform(patch("/v1/agents/" + agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header("If-Match", pausedEtag)
                        .content("{\"status\":\"on\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("on"));
        assertThat(agentControl("upsert")).hasSize(2);
        assertThat(agentControl("upsert").stream().filter(message -> Boolean.TRUE.equals(message.payload().get("backfill"))))
                .hasSize(1);
    }

    @Test
    void patchRejectsVersionConflictAndDeletedAgent() throws Exception {
        String agentId = createdAgentId(params(1900));
        mvc.perform(patch("/v1/agents/" + agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header("If-Match", "\"999\"")
                        .content("{\"status\":\"paused\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("version_conflict"));
        mvc.perform(delete("/v1/agents/" + agentId)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER))
                .andExpect(status().isNoContent());
        mvc.perform(patch("/v1/agents/" + agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header("If-Match", "\"0\"")
                        .content("{\"status\":\"on\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("agent_deleted"));
        mvc.perform(patch("/v1/agents/" + agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_other")
                        .header("If-Match", "\"0\"")
                        .content("{\"status\":\"paused\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rawAccountNumberIsRejected() throws Exception {
        mvc.perform(post("/v1/agents/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .content(confirmationBody(Map.of(
                                "from", "4111111111111111",
                                "to", "acct_tok_sav",
                                "amount", 100))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("raw_account_number"));
    }

    @Test
    void scheduleCreateSkipsAgentControlAndSetsTheNextFire() throws Exception {
        insertTemplate(scheduleTemplate());
        Map<String, Object> params = Map.of("cron", "0 0 9 * * *", "timezone", "America/New_York");
        String token = confirmationToken("weekly_bill_check", 1, params);
        MvcResult created = mvc.perform(post("/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .content(createBody("weekly_bill_check", 1, params, token, List.of())))
                .andExpect(status().isCreated())
                .andReturn();
        String agentId = objectMapper.readTree(created.getResponse().getContentAsString()).get("agentId").asText();
        PersonalAgent agent = mongoTemplate().findById(agentId, PersonalAgent.class);
        assertThat(agent.schedule()).isNotNull();
        assertThat(agent.schedule().nextFireAt()).isNotNull();
        assertThat(agent.trigger().schedule()).isEqualTo(new TriggerSchedule("0 0 9 * * *", "America/New_York"));
        assertThat(agentControl("upsert")).isEmpty();
    }

    private String createdAgentId(Map<String, Object> params) throws Exception {
        String token = confirmationToken(params);
        MvcResult created = mvc.perform(post("/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .content(createBody(params, token, List.of())))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("agentId").asText();
    }

    private String confirmationToken(Map<String, Object> params) throws Exception {
        return confirmationToken("payday_sweep", 1, params);
    }

    private String confirmationToken(String templateId, int version, Map<String, Object> params) throws Exception {
        MvcResult confirmation = mvc.perform(post("/v1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .content(confirmationBody(templateId, version, params)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(confirmation.getResponse().getContentAsString())
                .get("confirmationToken")
                .asText();
    }

    private String etag(String agentId) throws Exception {
        return mvc.perform(get("/v1/agents/" + agentId)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader("ETag");
    }

    private List<OutboxMessage> agentControl(String op) {
        return mongoTemplate().find(
                        Query.query(Criteria.where("destination").is(OutboxDestination.AGENT_CONTROL)),
                        OutboxMessage.class)
                .stream()
                .filter(message -> op.equals(message.payload().get("op")))
                .toList();
    }

    private String confirmationBody(Map<String, Object> params) throws Exception {
        return confirmationBody("payday_sweep", 1, params);
    }

    private String confirmationBody(String templateId, int version, Map<String, Object> params) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "templateId", templateId,
                "templateVersion", version,
                "params", params));
    }

    private String createBody(Map<String, Object> params, String token, List<String> tools) throws Exception {
        return createBody("payday_sweep", 1, params, token, tools);
    }

    private String createBody(
            String templateId, int version, Map<String, Object> params, String token, List<String> tools)
            throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "templateId", templateId,
                "templateVersion", version,
                "params", params,
                "confirmationToken", token,
                "sourceUtterance", "Move anything over 5000 on payday",
                "sessionId", "s1",
                "tools", tools));
    }

    private static Map<String, Object> params(int amount) {
        return Map.of("from", "acct_tok_chk", "to", "acct_tok_sav", "amount", amount);
    }

    private void insertTemplate(AgentTemplate template) {
        mongoTemplate().insert(template);
    }

    private static AgentTemplate payday() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        return new AgentTemplate(
                "payday_sweep:1",
                "payday_sweep",
                1,
                "Payday sweep",
                "Move extra payday money",
                new TemplateTrigger("payroll_credit"),
                new TemplateAction("transfer", "transfer.preview", "transfer.execute"),
                AgentMode.ASK,
                Map.of(
                        "type", "object",
                        "required", List.of("from", "to", "amount"),
                        "properties", Map.of(
                                "from", Map.of("type", "string"),
                                "to", Map.of("type", "string"),
                                "amount", Map.of("type", "number")),
                        "additionalProperties", false),
                new TemplateLimits(new BigDecimal("10000"), 1, null),
                List.of("transfer.preview", "transfer.execute"),
                Duration.ofHours(24),
                "Move payday money above the amount you set.",
                "approved",
                "reviewer",
                now,
                now,
                now,
                1);
    }

    private static AgentTemplate draftTemplate() {
        AgentTemplate approved = payday();
        return new AgentTemplate(
                "payday_sweep:2",
                "payday_sweep",
                2,
                approved.name(),
                approved.description(),
                approved.trigger(),
                approved.action(),
                approved.mode(),
                approved.paramSchema(),
                approved.limits(),
                approved.requiredTools(),
                approved.approvalTtl(),
                approved.effectText(),
                "draft",
                null,
                null,
                approved.createdAt(),
                approved.updatedAt(),
                1);
    }

    private static AgentTemplate scheduleTemplate() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        return new AgentTemplate(
                "weekly_bill_check:1",
                "weekly_bill_check",
                1,
                "Weekly bill check",
                "Read upcoming bills",
                new TemplateTrigger("schedule"),
                new TemplateAction("bill.read", "bill.read", null),
                AgentMode.READ,
                Map.of(
                        "type", "object",
                        "required", List.of("cron", "timezone"),
                        "properties", Map.of(
                                "cron", Map.of("type", "string"),
                                "timezone", Map.of("type", "string")),
                        "additionalProperties", false),
                new TemplateLimits(null, 1, null),
                List.of("bill.read"),
                Duration.ofHours(24),
                "Check upcoming bills.",
                "approved",
                "reviewer",
                now,
                now,
                now,
                1);
    }
}
