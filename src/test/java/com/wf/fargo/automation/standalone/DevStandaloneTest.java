package com.wf.fargo.automation.standalone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.runs.TriggerFixtures;
import com.wf.fargo.automation.store.IndexInitializer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class DevStandaloneTest extends EmbeddedMongoTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private IndexInitializer indexInitializer;

    @Autowired
    private TemplateSeeder seeder;

    @BeforeEach
    void indexes() {
        indexInitializer.ensureIndexes();
    }

    @Test
    void seederLoadsTheFourTemplates() throws Exception {
        seeder.seed();
        List<String> ids = mongoTemplate().findAll(AgentTemplate.class).stream()
                .map(AgentTemplate::templateId)
                .sorted()
                .toList();
        assertThat(ids).containsExactly(
                "foreign_charge_guard", "payday_sweep", "subscription_price_guard", "weekly_bill_check");
    }

    @Test
    void devTriggerPublishesATriggerTheConsumerHandles() throws Exception {
        TriggerFixtures.seed(mongoTemplate(), "agt_1", AgentStatus.ON, 5);
        mvc.perform(post("/v1/dev/trigger/agt_1")
                        .contentType("application/json")
                        .content("{\"periodId\":\"2026-10-15\"}"))
                .andExpect(status().isAccepted());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        List<AgentRun> runs = List.of();
        while (System.nanoTime() < deadline) {
            runs = mongoTemplate().findAll(AgentRun.class);
            if (!runs.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().agentId()).isEqualTo("agt_1");
        assertThat(runs.getFirst().periodId()).isEqualTo("2026-10-15");
    }

    @Test
    void nextReplyAndPushesEndpointsRespond() throws Exception {
        mvc.perform(post("/v1/dev/replies/next")
                        .contentType("application/json")
                        .content("{\"status\":\"error\",\"error\":{\"code\":\"declined\",\"message\":\"no\",\"retryable\":false}}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/dev/pushes")).andExpect(status().isOk());
    }
}
