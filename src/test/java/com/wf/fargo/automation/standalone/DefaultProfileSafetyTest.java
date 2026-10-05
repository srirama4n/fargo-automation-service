package com.wf.fargo.automation.standalone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wf.fargo.automation.domain.AgentTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("default")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "de.flapdoodle.mongodb.embedded.version=7.0.12",
        "spring.mongodb.embedded.storage.repl-set-name=rs0",
        "spring.kafka.listener.auto-startup=false",
        "spring.task.scheduling.enabled=false"
})
class DefaultProfileSafetyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void devEndpointsAreAbsentAndTheDevCustomerHeaderIsIgnored() throws Exception {
        assertThat(context.getBeanNamesForType(DevAuthFilter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DevTriggerController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(StubPushClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(StubAccountsClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FakeOrchestrator.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FakeSupervisorA2a.class)).isEmpty();
        assertThat(context.getBeanNamesForType(TemplateSeeder.class)).isEmpty();

        mvc.perform(post("/v1/dev/trigger").contentType("application/json").content("{\"periodId\":\"2026-10-15\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/v1/customers/cust_1/agents").header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_1"))
                .andExpect(status().isUnauthorized());

        assertThat(mongoTemplate.findAll(AgentTemplate.class)).isEmpty();
    }
}
