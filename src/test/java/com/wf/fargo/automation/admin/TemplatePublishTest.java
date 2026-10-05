package com.wf.fargo.automation.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.standalone.DevAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class TemplatePublishTest extends EmbeddedMongoTest {

    private static final String BODY = """
            {
              "templateId": "weekly_bill_check",
              "version": 1,
              "name": "Weekly bill check",
              "description": "Read upcoming bills",
              "trigger": { "type": "schedule" },
              "action": { "type": "bill.read", "previewIntent": "bill.read", "executeIntent": "bill.read" },
              "mode": "read",
              "paramSchema": {},
              "limits": { "maxRunsPerDay": 1 },
              "requiredTools": ["bill.read"],
              "approvalTtl": "PT24H",
              "effectText": "Shows upcoming bills.",
              "reviewedBy": "reviewer"
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Test
    void publishStoresAnApprovedVersionAndRejectsASecondWrite() throws Exception {
        mvc.perform(post("/v1/admin/templates").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/v1/admin/templates")
                        .header(DevAuthFilter.CALLER_HEADER, "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.templateId").value("weekly_bill_check"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("approved"));

        AgentTemplate stored = mongoTemplate().findOne(
                Query.query(Criteria.where("templateId").is("weekly_bill_check").and("version").is(1)),
                AgentTemplate.class);
        org.assertj.core.api.Assertions.assertThat(stored).isNotNull();
        org.assertj.core.api.Assertions.assertThat(stored.status()).isEqualTo("approved");
        org.assertj.core.api.Assertions.assertThat(stored.reviewedBy()).isEqualTo("reviewer");
        org.assertj.core.api.Assertions.assertThat(stored.requiredTools()).containsExactly("bill.read");

        mvc.perform(get("/v1/templates")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_1")
                        .param("status", "approved"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates[?(@.templateId == 'weekly_bill_check')].name")
                        .value("Weekly bill check"));

        mvc.perform(post("/v1/admin/templates")
                        .header(DevAuthFilter.CALLER_HEADER, "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("version_conflict"));
    }
}
