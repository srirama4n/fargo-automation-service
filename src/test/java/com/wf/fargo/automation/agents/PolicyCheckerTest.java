package com.wf.fargo.automation.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.common.ApiError;
import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.domain.AgentMode;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.domain.TemplateAction;
import com.wf.fargo.automation.domain.TemplateLimits;
import com.wf.fargo.automation.domain.TemplateTrigger;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PolicyCheckerTest extends EmbeddedMongoTest {

    @Autowired
    private PolicyChecker checker;

    @Autowired
    private TemplateService templates;

    @Autowired
    private AppProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void template() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        mongoTemplate().insert(new AgentTemplate(
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
                                "amount", Map.of("type", "number"),
                                "currency", Map.of("type", "string")),
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
                1));
    }

    @Test
    void rejectsEachBrokenRule() {
        assertThat(codes(checker.check("cust_1", "missing", 1, params(10, "USD")))).contains("template_not_found");
        assertThat(codes(checker.check("cust_1", "payday_sweep", 1, params(0, "USD")))).contains("not_positive");
        assertThat(codes(checker.check("cust_1", "payday_sweep", 1, params(10, "EUR")))).contains("unsupported_currency");
        assertThat(codes(checker.check("cust_1", "payday_sweep", 1, Map.of("amount", 10)))).contains("invalid_param");

        PolicyChecker rejecting = new PolicyChecker(
                mongoTemplate(), templates, (customerId, token) -> false, properties, objectMapper);
        assertThat(codes(rejecting.check("cust_1", "payday_sweep", 1, params(10, "USD"))))
                .contains("account_not_owned");
    }

    private static List<String> codes(PolicyChecker.PolicyCheck check) {
        return check.errors().stream().map(ApiError::code).toList();
    }

    private static Map<String, Object> params(int amount, String currency) {
        return Map.of("from", "acct_tok_chk", "to", "acct_tok_sav", "amount", amount, "currency", currency);
    }
}
