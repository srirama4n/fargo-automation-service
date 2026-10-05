package com.wf.fargo.automation.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wf.fargo.automation.domain.AgentMode;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.domain.AgentTrigger;
import com.wf.fargo.automation.domain.Approval;
import com.wf.fargo.automation.domain.ApprovalStatus;
import com.wf.fargo.automation.domain.Grant;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.TemplateAction;
import com.wf.fargo.automation.domain.TemplateLimits;
import com.wf.fargo.automation.domain.TemplateTrigger;
import com.wf.fargo.automation.standalone.DevAuthFilter;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

public class SubscriptionGuardSteps {

    private static final Instant SEEDED = Instant.parse("2026-10-01T00:00:00Z");
    private static final String CARD = "card_tok_9034";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private PaydaySweepSteps payday;

    @Given("customer {string} has a {string} agent {string}")
    public void subscriptionAgent(String customerId, String templateId, String agentId) {
        TemplateAction action = new TemplateAction(
                "card.merchant_block", "card.merchant_block.preview", "card.merchant_block.execute");
        mongoTemplate.insert(new AgentTemplate(
                templateId + ":1",
                templateId,
                1,
                "Subscription price guard",
                "Ask before a subscription price increase",
                new TemplateTrigger("recurring_charge_increased"),
                action,
                AgentMode.ASK,
                Map.of(),
                new TemplateLimits(null, null, null),
                List.of(action.previewIntent(), action.executeIntent()),
                Duration.ofHours(24),
                "Block a higher subscription charge.",
                "approved",
                "reviewer",
                SEEDED,
                SEEDED,
                SEEDED,
                1));
        mongoTemplate.insert(new PersonalAgent(
                agentId,
                customerId,
                templateId,
                1,
                "Subscription price guard",
                Map.of("card", CARD, "merchant", "m_streamflix"),
                new AgentTrigger("recurring_charge_increased", List.of(CARD), Map.of(), null),
                null,
                null,
                AgentMode.ASK,
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
                List.of(action.previewIntent(), action.executeIntent()),
                List.of(CARD),
                Map.of(),
                SEEDED,
                null,
                null,
                null,
                SEEDED,
                SEEDED,
                1));
    }

    @When("a trigger fires for {string} with merchant {string} from {double} to {double}")
    public void merchantTrigger(String agentId, String merchantId, double oldAmount, double newAmount) throws Exception {
        PersonalAgent agent = mongoTemplate.findById(agentId, PersonalAgent.class);
        assertThat(agent).isNotNull();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("merchantId", merchantId);
        context.put("merchantName", "StreamFlix");
        context.put("oldAmount", oldAmount);
        context.put("newAmount", newAmount);
        context.put("cardToken", CARD);
        context.put("cardDisplay", "Debit ••9034");
        String body = objectMapper.writeValueAsString(Map.of(
                "schemaVersion", 1,
                "messageId", "msg-" + merchantId + "-" + System.nanoTime(),
                "occurredAt", "2026-10-15T13:05:00Z",
                "customerId", agent.customerId(),
                "agentId", agentId,
                "periodId", merchantId,
                "eventRef", "chg_" + merchantId,
                "triggerType", "recurring_charge_increased",
                "context", context));
        kafka.send("trigger-fired", agent.customerId(), body).get(10, TimeUnit.SECONDS);
        payday.awaitTrigger();
    }

    @When("the customer denies the approval")
    public void deny() throws Exception {
        Approval approval = mongoTemplate.findOne(
                Query.query(Criteria.where("status").is(ApprovalStatus.PENDING)), Approval.class);
        assertThat(approval).isNotNull();
        mvc.perform(post("/v1/approvals/" + approval.approvalId() + "/deny")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, approval.customerId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("denied"));
    }

    @Then("a baseline update for {string} with amount {double} is published")
    public void baseline(String merchantId, double amount) {
        List<OutboxMessage> messages = mongoTemplate.find(
                Query.query(Criteria.where("destination").is(OutboxDestination.BASELINE_UPDATES)), OutboxMessage.class);
        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message.key()).isEqualTo("cust_2");
            Map<String, Object> payload = message.payload();
            assertThat(payload.get("schemaVersion")).isEqualTo(1);
            assertThat(payload.get("messageId")).isNotNull();
            assertThat(payload.get("occurredAt")).isNotNull();
            assertThat(payload.get("customerId")).isEqualTo("cust_2");
            assertThat(payload.get("cardToken")).isEqualTo(CARD);
            assertThat(payload.get("merchantId")).isEqualTo(merchantId);
            assertThat(((Number) payload.get("amount")).doubleValue()).isCloseTo(amount, offset(0.000_001));
            assertThat(payload.get("reason")).isEqualTo("approval_denied");
        });
    }
}
