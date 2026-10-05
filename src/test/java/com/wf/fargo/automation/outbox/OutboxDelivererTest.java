package com.wf.fargo.automation.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxDelivererTest extends OutboxIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppProperties properties;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Test
    void orchestratorTaskIsSentWithAGrantTokenThatIsNotWrittenBack() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", "tsk_1");
        payload.put("phase", "preview");
        OutboxMessage message = sample(OutboxDestination.ORCHESTRATOR_TASK, payload);
        GrantTokenMinter minter = task -> {
            assertThat(task).doesNotContainKey("grantToken");
            return "gt_test";
        };
        deliverer(minter, null).deliver(message);

        assertThat(payload).doesNotContainKey("grantToken");
        JsonNode sent = objectMapper.readTree(awaitValue(properties.topics().orchestratorInput(), "tsk_1"));
        assertThat(sent.get("grantToken").asText()).isEqualTo("gt_test");
        assertThat(sent.get("taskId").asText()).isEqualTo("tsk_1");
    }

    @Test
    void a2aDestinationIsHandedToTheA2aDeliverer() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", "tsk_a2a");
        OutboxMessage message = sample(OutboxDestination.A2A_TASK, payload);
        List<OutboxMessage> handed = new ArrayList<>();
        deliverer(task -> "gt_a2a", handed::add).deliver(message);

        assertThat(payload).doesNotContainKey("grantToken");
        assertThat(handed).hasSize(1);
        assertThat(handed.getFirst().payload()).containsEntry("grantToken", "gt_a2a");
        assertThat(handed.getFirst().destination()).isEqualTo(OutboxDestination.A2A_TASK);
    }

    @Test
    void pushPayloadIsPostedWithoutAGrantToken() {
        List<Map<String, Object>> posted = new ArrayList<>();
        Map<String, Object> payload = new HashMap<>();
        payload.put("customerId", "cust_1");
        payload.put("body", "Approved");
        OutboxDeliverer pushDeliverer = new OutboxDeliverer(
                kafka, objectMapper, properties, posted::add, task -> "unused", null);
        pushDeliverer.deliver(sample(OutboxDestination.PUSH, payload));
        assertThat(posted).containsExactly(payload);
    }

    private OutboxDeliverer deliverer(GrantTokenMinter minter, A2aTaskDeliverer a2a) {
        return new OutboxDeliverer(kafka, objectMapper, properties, payload -> {}, minter, a2a);
    }

    private static OutboxMessage sample(OutboxDestination destination, Map<String, Object> payload) {
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        return new OutboxMessage(
                "msg_deliver",
                destination,
                "cust_1",
                payload,
                "pending",
                0,
                now,
                null,
                null,
                null,
                now,
                now,
                1);
    }

    private String awaitValue(String topic, String marker) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-deliver-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                for (var record : records) {
                    if (record.value().contains(marker)) {
                        assertThat(record.key()).isEqualTo("cust_1");
                        return record.value();
                    }
                }
            }
        }
        throw new AssertionError("no kafka record containing " + marker);
    }
}
