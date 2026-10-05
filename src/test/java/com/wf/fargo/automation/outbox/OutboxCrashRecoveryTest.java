package com.wf.fargo.automation.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.store.OutboxMessageRepository;
import com.wf.fargo.automation.store.TransactionRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

class OutboxCrashRecoveryTest extends OutboxIntegrationTest {

    @Autowired
    private TransactionRunner transactions;

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private OutboxDeliverer deliverer;

    @Autowired
    private OutboxMessageRepository outbox;

    @Autowired
    private AppProperties properties;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Test
    void crashBetweenSendAndMarkSendsAgainAndDoesNotLoseTheMessage() throws Exception {
        String marker = "crash-" + UUID.randomUUID();
        MutableClock clock = new MutableClock(Instant.now());
        AtomicInteger attempts = new AtomicInteger();
        Consumer<OutboxMessage> delivery = message -> {
            deliverer.deliver(message);
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("killed after send");
            }
        };
        OutboxRelay relay = new OutboxRelay(mongoTemplate(), clock, properties.outbox(), delivery);

        OutboxMessage written = transactions.inTransaction(() -> {
            OutboxMessage message = writer.write(
                    OutboxDestination.AGENT_CONTROL, "cust_1", Map.of("op", "upsert", "marker", marker));
            return message;
        });
        clock.set(Instant.now().plusSeconds(5));

        relay.dispatchDue();

        OutboxMessage afterKill = outbox.findById(written.messageId()).orElseThrow();
        assertThat(afterKill.status()).isEqualTo("pending");
        assertThat(afterKill.sentAt()).isNull();
        assertThat(afterKill.lockedBy()).isNull();
        assertThat(afterKill.attempts()).isEqualTo(1);
        assertThat(kafkaValues(properties.topics().agentControl(), marker, 1)).hasSize(1);

        clock.set(afterKill.nextAttemptAt());
        relay.dispatchDue();

        OutboxMessage recovered = outbox.findById(written.messageId()).orElseThrow();
        assertThat(recovered.status()).isEqualTo("sent");
        assertThat(recovered.sentAt()).isNotNull();
        assertThat(kafkaValues(properties.topics().agentControl(), marker, 2)).hasSize(2);
    }

    @Test
    void expiredLeaseAfterAKillIsDeliveredAgain() throws Exception {
        Instant start = Instant.parse("2026-10-04T12:00:00Z");
        MutableClock clock = new MutableClock(start);
        String marker = "lease-" + UUID.randomUUID();
        AtomicInteger deliveries = new AtomicInteger();
        Consumer<OutboxMessage> delivery = message -> {
            deliveries.incrementAndGet();
            deliverer.deliver(message);
        };
        OutboxRelay relay = new OutboxRelay(mongoTemplate(), clock, properties.outbox(), delivery);
        Instant lockedUntil = start.plus(properties.outbox().leaseDuration());
        outbox.save(pending("msg_lease", OutboxDestination.AGENT_CONTROL, marker, start, "dead-instance", lockedUntil));

        relay.dispatchDue();
        assertThat(deliveries).hasValue(0);
        assertThat(outbox.findById("msg_lease").orElseThrow().status()).isEqualTo("pending");

        clock.set(lockedUntil.plusSeconds(1));
        relay.dispatchDue();

        assertThat(deliveries).hasValue(1);
        assertThat(outbox.findById("msg_lease").orElseThrow().status()).isEqualTo("sent");
        assertThat(kafkaValues(properties.topics().agentControl(), marker, 1)).hasSize(1);
    }

    private static OutboxMessage pending(
            String id,
            OutboxDestination destination,
            String marker,
            Instant now,
            String lockedBy,
            Instant lockedUntil) {
        return new OutboxMessage(
                id,
                destination,
                "cust_1",
                Map.of("marker", marker),
                "pending",
                0,
                now,
                lockedBy,
                lockedUntil,
                null,
                now,
                now,
                1);
    }

    private List<String> kafkaValues(String topic, String marker, int expected) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-crash-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> values = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (values.size() < expected && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                records.forEach(record -> {
                    if (record.value().contains(marker)) {
                        values.add(record.value());
                    }
                });
            }
        }
        return values;
    }
}
