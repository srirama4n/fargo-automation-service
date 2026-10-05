package com.wf.fargo.automation.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.store.OutboxMessageRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OutboxRelayWakeTest extends OutboxIntegrationTest {

    @Autowired
    private OutboxMessageRepository outbox;

    @Autowired
    private AppProperties properties;

    @Test
    void insertWakesTheRelayThroughTheChangeStream() throws Exception {
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        MutableClock clock = new MutableClock(now.plusSeconds(1));
        OutboxRelay relay = relay(clock, Duration.ofHours(1), message -> {});
        try {
            relay.start();
            outbox.save(pending("msg_insert", now));
            assertThat(awaitStatus("msg_insert", "sent")).isTrue();
        } finally {
            relay.stop();
        }
    }

    @Test
    void pollDeliversAMessageThatWasNotDueWhenItWasInserted() throws Exception {
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        AtomicInteger deliveries = new AtomicInteger();
        OutboxRelay relay = relay(clock, Duration.ofMillis(50), message -> deliveries.incrementAndGet());
        try {
            outbox.save(pending("msg_later", now.plus(Duration.ofHours(1))));
            relay.start();
            Thread.sleep(200);
            assertThat(deliveries).hasValue(0);
            assertThat(outbox.findById("msg_later").orElseThrow().status()).isEqualTo("pending");

            clock.set(now.plus(Duration.ofHours(1)));
            assertThat(awaitStatus("msg_later", "sent")).isTrue();
            assertThat(deliveries).hasValue(1);
        } finally {
            relay.stop();
        }
    }

    private OutboxRelay relay(MutableClock clock, Duration pollInterval, java.util.function.Consumer<OutboxMessage> delivery) {
        AppProperties.Outbox settings = new AppProperties.Outbox(
                pollInterval, properties.outbox().leaseDuration(), properties.outbox().maxAttempts());
        return new OutboxRelay(mongoTemplate(), clock, settings, delivery);
    }

    private boolean awaitStatus(String id, String status) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            OutboxMessage stored = outbox.findById(id).orElseThrow();
            if (status.equals(stored.status())) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static OutboxMessage pending(String id, Instant due) {
        Instant created = Instant.parse("2026-10-04T12:00:00Z");
        return new OutboxMessage(
                id,
                OutboxDestination.BASELINE_UPDATES,
                "cust_1",
                Map.of("reason", "approval_denied"),
                "pending",
                0,
                due,
                null,
                null,
                null,
                created,
                created,
                1);
    }
}
