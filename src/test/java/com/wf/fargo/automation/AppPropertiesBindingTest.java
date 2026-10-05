package com.wf.fargo.automation;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.fargo.automation.approvals.CvamsWireMock;
import com.wf.fargo.automation.config.AppProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AppPropertiesBindingTest extends EmbeddedMongoTest {

    @Autowired
    private AppProperties properties;

    @Test
    void bindsSectionFiveAndStandaloneDefaults() {
        assertThat(properties.dispatch().mode()).isEqualTo("kafka");
        assertThat(properties.topics().triggerFired()).isEqualTo("trigger-fired");
        assertThat(properties.topics().automationReplies()).isEqualTo("automation-replies");
        assertThat(properties.topics().agentControl()).isEqualTo("agent-control");
        assertThat(properties.topics().baselineUpdates()).isEqualTo("baseline-updates");
        assertThat(properties.topics().orchestratorInput()).isEqualTo("fargo-orchestrator-input");
        assertThat(properties.limits().maxAgentsPerCustomer()).isEqualTo(20);
        assertThat(properties.limits().confirmationTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.limits().grantTokenTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.limits().defaultApprovalTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.limits().stuckRunTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.sweeper().approvalExpiryCron()).isEqualTo("0 * * * * *");
        assertThat(properties.sweeper().stuckRunCron()).isEqualTo("30 * * * * *");
        assertThat(properties.scheduler().pollInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.scheduler().batchSize()).isEqualTo(200);
        assertThat(properties.outbox().pollInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.outbox().leaseDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.outbox().maxAttempts()).isEqualTo(20);
        assertThat(properties.push().baseUrl()).isEqualTo("https://push.internal.example");
        assertThat(properties.cvams().baseUrl()).isEqualTo(CvamsWireMock.baseUrl());
        assertThat(classpathYaml()).contains("baseUrl: https://cvams.internal.example");
        assertThat(properties.a2a().supervisorUrl()).isEqualTo("https://supervisor.internal.example");
        assertThat(properties.a2a().timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.standalone().replyDelay()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.standalone().mongoDataDir()).isEmpty();
    }

    @Test
    void defaultsStandaloneWhenTheBlockIsAbsent() {
        AppProperties withoutStandalone = new AppProperties(
                properties.dispatch(),
                properties.topics(),
                properties.limits(),
                properties.sweeper(),
                properties.scheduler(),
                properties.outbox(),
                properties.push(),
                properties.cvams(),
                properties.a2a(),
                null);
        assertThat(withoutStandalone.standalone().replyDelay()).isEqualTo(Duration.ofSeconds(2));
        assertThat(withoutStandalone.standalone().mongoDataDir()).isEmpty();
    }

    private static String classpathYaml() {
        try (var source = AppPropertiesBindingTest.class.getResourceAsStream("/application.yml")) {
            return new String(source.readAllBytes());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
