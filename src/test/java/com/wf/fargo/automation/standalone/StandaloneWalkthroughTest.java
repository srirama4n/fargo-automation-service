package com.wf.fargo.automation.standalone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.domain.AgentRun;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("standalone")
@AutoConfigureMockMvc
class StandaloneWalkthroughTest {

    private static final String CUSTOMER = "cust_1";
    private static final String PARAMS =
            "{\"from\":\"acct_tok_chk\",\"to\":\"acct_tok_sav\",\"amountAbove\":5000}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void manualWalkthroughCompletesFromCreateToDone() throws Exception {
        MvcResult confirmation = mvc.perform(post("/v1/confirmations")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"payday_sweep\",\"templateVersion\":1,\"params\":" + PARAMS + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(confirmation.getResponse().getContentAsString())
                .get("confirmationToken")
                .asText();

        MvcResult created = mvc.perform(post("/v1/agents")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"payday_sweep\",\"templateVersion\":1,\"params\":" + PARAMS
                                + ",\"confirmationToken\":" + objectMapper.writeValueAsString(token)
                                + ",\"sourceUtterance\":\"Move anything over 5000 on payday\",\"sessionId\":\"s1\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String agentId = objectMapper.readTree(created.getResponse().getContentAsString()).get("agentId").asText();

        mvc.perform(post("/v1/dev/trigger/" + agentId)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodId\":\"2026-10-15\"}"))
                .andExpect(status().isAccepted());

        String approvalId = await().atMost(Duration.ofSeconds(30)).until(() -> approvalIdFromPushes(), id -> id != null);

        mvc.perform(post("/v1/approvals/" + approvalId + "/approve")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER)
                        .header("X-StepUp-Token", StubCvamsClient.ACCEPTED_TOKEN))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode items = activityItems();
            assertThat(items.findValuesAsText("type")).contains("completed");
            assertThat(items.findValuesAsText("message")).anyMatch(message -> message.contains("is done."));
        });

        List<AgentRun> runs = mongoTemplate.find(Query.query(Criteria.where("agentId").is(agentId)), AgentRun.class);
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().state()).isEqualTo(RunState.DONE);
        List<OutboxMessage> tasks = mongoTemplate.find(
                Query.query(Criteria.where("destination").is(OutboxDestination.ORCHESTRATOR_TASK)),
                OutboxMessage.class);
        assertThat(tasks).hasSize(2);
        Object traceId = tasks.getFirst().payload().get("traceId");
        assertThat(traceId).isNotNull();
        assertThat(tasks).allSatisfy(message -> assertThat(message.payload()).containsEntry("traceId", traceId));
    }

    private String approvalIdFromPushes() {
        try {
            JsonNode pushes = objectMapper.readTree(mvc.perform(get("/v1/dev/pushes")
                            .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
                    .path("pushes");
            for (JsonNode push : pushes) {
                String link = push.path("deepLink").asText("");
                String marker = "wf://fargo/approvals/";
                if (link.startsWith(marker) && "agent_approval".equals(push.path("category").asText())) {
                    return link.substring(marker.length());
                }
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private JsonNode activityItems() throws Exception {
        MvcResult result = mvc.perform(get("/v1/customers/" + CUSTOMER + "/activity")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, CUSTOMER))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("items");
    }
}
