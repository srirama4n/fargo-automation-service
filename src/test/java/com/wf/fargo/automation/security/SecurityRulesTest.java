package com.wf.fargo.automation.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.approvals.StepUpVerifier;
import com.wf.fargo.automation.common.ApiException;
import com.wf.fargo.automation.common.CallerContext;
import com.wf.fargo.automation.config.AccessTokens;
import com.wf.fargo.automation.domain.AdminJob;
import com.wf.fargo.automation.domain.AgentMode;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.AgentTrigger;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.standalone.DevAuthFilter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class SecurityRulesTest extends EmbeddedMongoTest {

    private static final Instant SEEDED = Instant.parse("2026-10-01T00:00:00Z");
    private static final String TOKEN = "acct_tok_abcdWXYZ";
    private static final String PAN = "4111111111111111";
    private static final Pattern RAW_DIGITS = Pattern.compile("\\d{13,19}");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AccessTokens tokens;

    @Autowired
    private StepUpVerifier stepUp;

    @Test
    void anotherCustomersAgentIsNotFound() throws Exception {
        mongoTemplate().insert(agent("agt_owned", "cust_owner"));
        ListAppender<ILoggingEvent> logs = attach("com.wf.fargo.automation.agents.AgentService");
        try {
            mvc.perform(get("/v1/agents/agt_owned").header("Authorization", "Bearer " + tokens.customerToken("cust_other")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("not_found"))
                    .andExpect(jsonPath("$.status").value(404));
            assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("security=true")
                    && event.getFormattedMessage().contains("ownership_mismatch"));
            assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("cust_owner")
                    || event.getFormattedMessage().contains("cust_other"));
        } finally {
            detach("com.wf.fargo.automation.agents.AgentService", logs);
        }
    }

    @Test
    void customerJwtIsCheckedForIssuerAudienceAndExpiry() throws Exception {
        mvc.perform(get("/v1/customers/cust_jwt/agents")
                        .header("Authorization", "Bearer " + tokens.customerToken("cust_jwt")))
                .andExpect(status().isOk());

        String expired = tokens.customerToken(
                "cust_jwt", AccessTokens.CUSTOMER_ISSUER, AccessTokens.CUSTOMER_AUDIENCE, Instant.EPOCH);
        mvc.perform(get("/v1/customers/cust_jwt/agents").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));

        String wrongAudience = tokens.customerToken(
                "cust_jwt", AccessTokens.CUSTOMER_ISSUER, "wrong-audience", null);
        mvc.perform(get("/v1/customers/cust_jwt/agents").header("Authorization", "Bearer " + wrongAudience))
                .andExpect(status().isUnauthorized());

        String wrongIssuer = tokens.customerToken(
                "cust_jwt", "wrong-issuer", AccessTokens.CUSTOMER_AUDIENCE, null);
        mvc.perform(get("/v1/customers/cust_jwt/agents").header("Authorization", "Bearer " + wrongIssuer))
                .andExpect(status().isUnauthorized());

        String valid = tokens.customerToken("cust_jwt");
        String tampered = valid.substring(0, valid.length() - 2) + "aa";
        mvc.perform(get("/v1/customers/cust_jwt/agents").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerJwtWinsOverTheDevHeader() throws Exception {
        mvc.perform(get("/v1/customers/cust_other/agents")
                        .header("Authorization", "Bearer " + tokens.customerToken("cust_jwt"))
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_other"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void mcpHeaderIsTrustedOnlyForTheMcpIdentity() throws Exception {
        mongoTemplate().insert(agent("agt_owned", "cust_owner"));
        mvc.perform(get("/v1/agents/agt_owned")
                        .header(DevAuthFilter.CALLER_HEADER, "mcp")
                        .header(CallerContext.FARGO_CUSTOMER_HEADER, "cust_owner"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/agents/agt_owned").header(CallerContext.FARGO_CUSTOMER_HEADER, "cust_owner"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
        mvc.perform(get("/v1/agents/agt_owned")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_other")
                        .header(CallerContext.FARGO_CUSTOMER_HEADER, "cust_owner"))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminSsoJwtRecordsTheAdminUserId() throws Exception {
        ListAppender<ILoggingEvent> logs = attach("com.wf.fargo.automation.admin.KillSwitchService");
        try {
            mvc.perform(post("/v1/admin/agents/pause-all")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + tokens.adminToken("ops_7"))
                            .content("{\"reason\":\"incident INC123\"}"))
                    .andExpect(status().isOk());
            assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().contains("admin=ops_7")
                    && event.getFormattedMessage().contains("incident INC123"));
            assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("eyJ"));
            AdminJob job = mongoTemplate().findAll(AdminJob.class).get(0);
            assertThat(job.admin()).isEqualTo("ops_7");
        } finally {
            detach("com.wf.fargo.automation.admin.KillSwitchService", logs);
        }

        String viewer = tokens.adminToken("ops_7", "viewer", null);
        mvc.perform(post("/v1/admin/agents/pause-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + viewer)
                        .content("{\"reason\":\"incident INC123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));

        mvc.perform(post("/v1/admin/agents/pause-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokens.customerToken("cust_1"))
                        .content("{\"reason\":\"incident INC123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rawAccountNumbersAreRejectedAndNeverLogged() throws Exception {
        ListAppender<ILoggingEvent> logs = attach("com.wf.fargo.automation");
        try {
            mvc.perform(post("/v1/confirmations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_1")
                            .content("""
                                    {"templateId":"payday_sweep","templateVersion":1,"params":{"from":%s,"to":"%s"}}
                                    """.formatted(PAN, TOKEN)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("raw_account_number"));
            Logger probe = (Logger) LoggerFactory.getLogger("com.wf.fargo.automation.security.capture");
            probe.info("payload {} and {}", TOKEN, PAN);
            probe.info("payload " + TOKEN + " and " + PAN);
            assertNoSecrets(logs.list);
            assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().contains("***WXYZ"));
        } finally {
            detach("com.wf.fargo.automation", logs);
        }
    }

    @Test
    void aShortDigitStringIsNotARawAccountNumber() throws Exception {
        mvc.perform(post("/v1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_1")
                        .content("""
                                {"templateId":"payday_sweep","templateVersion":1,"params":{"note":"123456789012"}}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void securityEventsAreWarnWithoutTheSecret() throws Exception {
        ListAppender<ILoggingEvent> logs = attach("com.wf.fargo.automation");
        try {
            mvc.perform(post("/v1/agents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_1")
                            .content("""
                                    {"templateId":"payday_sweep","templateVersion":1,"params":{"from":"acct_tok_chk"},
                                     "confirmationToken":"LEAKED_CONFIRMATION","sourceUtterance":"sweep","sessionId":"s1"}
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("confirmation_invalid"));
            try {
                stepUp.verify("LEAKED_STEP_UP_TOKEN", "cust_1", "apr_1");
            } catch (ApiException exception) {
                assertThat(exception.code()).isEqualTo("step_up_required");
            }
            assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("security=true")
                    && event.getFormattedMessage().contains("confirmation_invalid"));
            assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("security=true")
                    && event.getFormattedMessage().contains("step_up_failed"));
            assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("LEAKED_CONFIRMATION")
                    || event.getFormattedMessage().contains("LEAKED_STEP_UP_TOKEN"));
        } finally {
            detach("com.wf.fargo.automation", logs);
        }
    }

    private static void assertNoSecrets(List<ILoggingEvent> events) {
        assertThat(events).allSatisfy(event -> {
            String message = event.getFormattedMessage();
            assertThat(message).doesNotContain(TOKEN);
            assertThat(message).doesNotContain(PAN);
            assertThat(RAW_DIGITS.matcher(message).find()).isFalse();
        });
    }

    private static ListAppender<ILoggingEvent> attach(String name) {
        Logger logger = (Logger) LoggerFactory.getLogger(name);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(String name, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(name)).detachAppender(appender);
    }

    private static PersonalAgent agent(String agentId, String customerId) {
        return new PersonalAgent(
                agentId,
                customerId,
                "payday_sweep",
                1,
                "Payday",
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
                SEEDED,
                SEEDED,
                1);
    }
}
