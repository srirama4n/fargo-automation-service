package com.wf.fargo.automation.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.domain.TemplateAction;
import com.wf.fargo.automation.domain.TemplateLimits;
import com.wf.fargo.automation.domain.TemplateTrigger;
import com.wf.fargo.automation.standalone.DevAuthFilter;
import com.wf.fargo.automation.store.IndexInitializer;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

public class AgentLifecycleSteps {

    private static final String CUSTOMER = "cust_grant";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private IndexInitializer indexInitializer;

    private String confirmationToken;
    private String customerId;
    private MvcResult result;

    @Before
    public void clean() {
        for (String name : mongoTemplate.getDb().listCollectionNames()) {
            if (!name.startsWith("system.")) {
                mongoTemplate.dropCollection(name);
            }
        }
        indexInitializer.ensureIndexes();
    }

    @Given("customer {string} requested a confirmation for template {string} with amount {int}")
    public void customerRequestedAConfirmation(String customerId, String templateId, int amount) throws Exception {
        this.customerId = customerId;
        String body = objectMapper.writeValueAsString(Map.of(
                "templateId", templateId,
                "templateVersion", 1,
                "params", params(amount)));
        MvcResult confirmation = mvc.perform(post("/v1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, customerId)
                        .content(body))
                .andReturn();
        assertThat(confirmation.getResponse().getStatus()).isEqualTo(201);
        confirmationToken = objectMapper.readTree(confirmation.getResponse().getContentAsString())
                .get("confirmationToken")
                .asText();
    }

    @When("the builder creates the agent with amount {int} and that token")
    public void builderCreatesWithAmount(int amount) throws Exception {
        result = create(customerId, "payday_sweep", params(amount), confirmationToken, List.of());
    }

    @When("the builder creates an agent and the request also lists tool {string}")
    public void builderCreatesWithExtraTool(String tool) throws Exception {
        customerId = CUSTOMER;
        seedPaydayTemplate(List.of("transfer.preview", "transfer.execute"));
        Map<String, Object> params = params(5000);
        String body = objectMapper.writeValueAsString(Map.of(
                "templateId", "payday_sweep",
                "templateVersion", 1,
                "params", params));
        MvcResult confirmation = mvc.perform(post("/v1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, customerId)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .content(body))
                .andReturn();
        assertThat(confirmation.getResponse().getStatus()).isEqualTo(201);
        confirmationToken = objectMapper.readTree(confirmation.getResponse().getContentAsString())
                .get("confirmationToken")
                .asText();
        result = create(customerId, "payday_sweep", params, confirmationToken, List.of(tool));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    @Then("the request is rejected with {string}")
    public void requestIsRejected(String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo(code);
    }

    @Then("the grant's allowed tools equal the template's required tools")
    public void grantToolsMatchTemplate() throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String grantId = body.get("grantId").asText();
        Grant grant = mongoTemplate.findById(grantId, Grant.class);
        AgentTemplate template = mongoTemplate.findOne(
                Query.query(Criteria.where("templateId").is("payday_sweep").and("version").is(1)),
                AgentTemplate.class);
        assertThat(grant).isNotNull();
        assertThat(template).isNotNull();
        assertThat(grant.allowedTools()).containsExactlyElementsOf(template.requiredTools());
        assertThat(grant.allowedTools()).doesNotContain("transfer.execute_external");
    }

    @Given("agent {string} has a pending approval")
    public void agentHasAPendingApproval(String agentId) {
        customerId = "cust_4";
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        mongoTemplate.insert(new PersonalAgent(
                agentId,
                customerId,
                "payday_sweep",
                1,
                "Payday sweep",
                Map.of(),
                new AgentTrigger("payroll_credit", List.of(), Map.of(), null),
                null,
                null,
                AgentMode.ASK,
                AgentStatus.ON,
                "grt_" + agentId,
                null,
                null,
                0L,
                now,
                now,
                1));
        mongoTemplate.insert(new Grant(
                "grt_" + agentId,
                agentId,
                customerId,
                List.of("transfer.preview", "transfer.execute"),
                List.of(),
                Map.of(),
                now,
                null,
                null,
                null,
                now,
                now,
                1));
        mongoTemplate.insert(new AgentRun(
                "run_" + agentId,
                agentId,
                customerId,
                "2026-10-15",
                agentId + ":2026-10-15",
                "post_1",
                Map.of(),
                RunState.AWAITING_APPROVAL,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                new RunAttempts(1, 0),
                now,
                now,
                1));
        mongoTemplate.insert(new Approval(
                "apr_" + agentId,
                "run_" + agentId,
                agentId,
                customerId,
                "sha256:pending",
                ApprovalStatus.PENDING,
                now.plus(Duration.ofHours(24)),
                null,
                null,
                null,
                now,
                now,
                1));
    }

    @When("the customer deletes {string}")
    public void customerDeletes(String agentId) throws Exception {
        result = mvc.perform(delete("/v1/agents/" + agentId)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, customerId))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
    }

    @Then("the grant is revoked")
    public void grantIsRevoked() {
        Grant grant = mongoTemplate.findOne(
                Query.query(Criteria.where("agentId").is("agt_4")), Grant.class);
        assertThat(grant).isNotNull();
        assertThat(grant.revokedAt()).isNotNull();
        assertThat(grant.revokedReason()).isEqualTo("agent_deleted");
    }

    @Then("the approval is {string}")
    public void approvalIs(String status) {
        Approval approval = mongoTemplate.findById("apr_agt_4", Approval.class);
        assertThat(approval).isNotNull();
        assertThat(approval.status().name()).isEqualToIgnoringCase(status);
        AgentRun run = mongoTemplate.findById("run_agt_4", AgentRun.class);
        assertThat(run).isNotNull();
        assertThat(run.state()).isEqualTo(RunState.EXPIRED);
    }

    @Then("an agent-control {string} message is published")
    public void agentControlMessageIsPublished(String op) {
        List<OutboxMessage> messages = mongoTemplate.find(
                Query.query(Criteria.where("destination").is(OutboxDestination.AGENT_CONTROL)),
                OutboxMessage.class);
        assertThat(messages).anySatisfy(message -> assertThat(message.payload()).containsEntry("op", op));
    }

    private MvcResult create(
            String customerId,
            String templateId,
            Map<String, Object> params,
            String token,
            List<String> tools) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "templateId", templateId,
                "templateVersion", 1,
                "params", params,
                "confirmationToken", token,
                "sourceUtterance", "Move anything over 5000 on payday",
                "sessionId", "s1",
                "tools", tools));
        return mvc.perform(post("/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, customerId)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .content(body))
                .andReturn();
    }

    private void seedPaydayTemplate(List<String> requiredTools) {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        mongoTemplate.insert(new AgentTemplate(
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
                requiredTools,
                Duration.ofHours(24),
                "Move payday money above the amount you set.",
                "approved",
                "reviewer",
                now,
                now,
                now,
                1));
    }

    private static Map<String, Object> params(int amount) {
        return Map.of("from", "acct_tok_chk", "to", "acct_tok_sav", "amount", amount);
    }
}
