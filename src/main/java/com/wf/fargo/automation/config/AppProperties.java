package com.wf.fargo.automation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "automation")
public record AppProperties(
        Dispatch dispatch,
        Topics topics,
        Limits limits,
        Sweeper sweeper,
        Scheduler scheduler,
        Outbox outbox,
        Push push,
        Cvams cvams,
        A2a a2a,
        Standalone standalone) {

    public AppProperties {
        if (standalone == null) {
            standalone = new Standalone(Duration.ofSeconds(2), "");
        }
    }

    public record Dispatch(String mode) {}

    public record Topics(
            String triggerFired,
            String automationReplies,
            String agentControl,
            String baselineUpdates,
            String orchestratorInput) {}

    public record Limits(
            int maxAgentsPerCustomer,
            Duration confirmationTtl,
            Duration grantTokenTtl,
            Duration defaultApprovalTtl,
            Duration stuckRunTimeout) {}

    public record Sweeper(String approvalExpiryCron, String stuckRunCron) {}

    public record Scheduler(Duration pollInterval, int batchSize) {}

    public record Outbox(Duration pollInterval, Duration leaseDuration, int maxAttempts) {}

    public record Push(String baseUrl) {}

    public record Cvams(String baseUrl) {}

    public record A2a(String supervisorUrl, Duration timeout) {}

    public record Standalone(Duration replyDelay, String mongoDataDir) {}
}
