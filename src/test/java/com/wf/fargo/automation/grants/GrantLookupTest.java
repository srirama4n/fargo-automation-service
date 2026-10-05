package com.wf.fargo.automation.grants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.agents.GrantTokenSigner;
import com.wf.fargo.automation.domain.Grant;
import com.wf.fargo.automation.standalone.DevAuthFilter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class GrantLookupTest extends EmbeddedMongoTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GrantTokenSigner signer;

    @Test
    void mintedTokenVerifiesAgainstTheServedJwks() throws Exception {
        MvcResult jwks = mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].e").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andReturn();
        JsonNode key = objectMapper.readTree(jwks.getResponse().getContentAsString()).get("keys").get(0);
        String token = signer.sign("agt_1", "grt_1", "cust_1", "run_1", "tsk_1");

        assertThat(GrantTokenSignerTest.verify(token, key, Instant.now())).isTrue();
        assertThat(mongoTemplate().findAll(Grant.class)).isEmpty();
    }

    @Test
    void grantEndpointRejectsNonMcpCallers() throws Exception {
        insertGrant(false);
        mvc.perform(get("/v1/grants/grt_1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/grants/grt_1")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
        mvc.perform(get("/v1/grants/grt_1")
                        .header(DevAuthFilter.CALLER_HEADER, "admin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mcpCallerReadsTheGrantIncludingRevocation() throws Exception {
        insertGrant(false);
        mvc.perform(get("/v1/grants/grt_1")
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grantId").value("grt_1"))
                .andExpect(jsonPath("$.agentId").value("agt_1"))
                .andExpect(jsonPath("$.customerId").value("cust_1"))
                .andExpect(jsonPath("$.allowedTools[0]").value("transfer.preview"))
                .andExpect(jsonPath("$.accountScopes[0]").value("acct_tok_chk"))
                .andExpect(jsonPath("$.limits.maxRunsPerDay").value(1))
                .andExpect(jsonPath("$.revoked").value(false));

        insertGrant(true);
        mvc.perform(get("/v1/grants/grt_1").header(DevAuthFilter.CALLER_HEADER, "mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        mvc.perform(get("/v1/grants/missing").header(DevAuthFilter.CALLER_HEADER, "mcp"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    private void insertGrant(boolean revoked) {
        mongoTemplate().save(new Grant(
                "grt_1",
                "agt_1",
                "cust_1",
                List.of("transfer.preview", "transfer.execute"),
                List.of("acct_tok_chk"),
                Map.of("maxRunsPerDay", 1),
                Instant.parse("2026-10-01T00:00:00Z"),
                null,
                revoked ? Instant.parse("2026-10-02T00:00:00Z") : null,
                revoked ? "agent_deleted" : null,
                Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z"),
                1));
    }
}
