package com.wf.fargo.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;

class EmbeddedKafkaTopicsTest extends EmbeddedKafkaTest {

    @Test
    void createsSpecTopicsAndDeadLetterTopics() throws Exception {
        try (AdminClient admin = AdminClient.create(adminConfig())) {
            Set<String> names = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            assertThat(names).contains(
                    "trigger-fired",
                    "trigger-fired.dlt",
                    "automation-replies",
                    "automation-replies.dlt",
                    "agent-control",
                    "agent-control.dlt",
                    "baseline-updates",
                    "baseline-updates.dlt",
                    "fargo-orchestrator-input",
                    "fargo-orchestrator-input.dlt");
        }
    }
}
