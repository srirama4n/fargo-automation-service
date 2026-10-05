package com.wf.fargo.automation.runs;

import com.wf.fargo.automation.domain.AgentMode;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.domain.AgentTrigger;
import com.wf.fargo.automation.domain.Grant;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.TemplateAction;
import com.wf.fargo.automation.domain.TemplateLimits;
import com.wf.fargo.automation.domain.TemplateTrigger;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;

public final class TriggerFixtures {

    private static final Instant SEEDED = Instant.parse("2026-10-01T00:00:00Z");

    private TriggerFixtures() {}

    public static void seed(MongoTemplate mongo, String agentId, AgentStatus status, Integer maxRunsPerDay) {
        mongo.insert(new AgentTemplate(
                "payday_sweep:1",
                "payday_sweep",
                1,
                "Payday sweep",
                "Move extra payday money",
                new TemplateTrigger("payroll_credit"),
                new TemplateAction("transfer", "transfer.preview", "transfer.execute"),
                AgentMode.ASK,
                Map.of(),
                new TemplateLimits(new BigDecimal("10000"), maxRunsPerDay, null),
                List.of("transfer.preview", "transfer.execute"),
                Duration.ofHours(24),
                "Move payday money above the amount you set.",
                "approved",
                "reviewer",
                SEEDED,
                SEEDED,
                SEEDED,
                1));
        mongo.insert(new PersonalAgent(
                agentId,
                "cust_1",
                "payday_sweep",
                1,
                "Payday sweep",
                Map.of("from", "acct_tok_chk", "to", "acct_tok_sav", "amount", 5000),
                new AgentTrigger("payroll_credit", List.of("acct_tok_chk"), Map.of(), null),
                null,
                null,
                AgentMode.ASK,
                status,
                "grt_" + agentId,
                null,
                null,
                0L,
                SEEDED,
                SEEDED,
                1));
        mongo.insert(new Grant(
                "grt_" + agentId,
                agentId,
                "cust_1",
                List.of("transfer.preview", "transfer.execute"),
                List.of("acct_tok_chk", "acct_tok_sav"),
                Map.of(),
                SEEDED,
                null,
                null,
                null,
                SEEDED,
                SEEDED,
                1));
    }

    public static TriggerFired fired(String agentId, String periodId) {
        return new TriggerFired(
                1,
                "m-" + periodId,
                Instant.parse("2026-10-15T13:05:00Z"),
                "cust_1",
                agentId,
                periodId,
                "post_" + periodId,
                "payroll_credit",
                Map.of("amount", 1000.00, "currency", "USD"));
    }
}
