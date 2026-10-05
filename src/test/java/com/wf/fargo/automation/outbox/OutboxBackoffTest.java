package com.wf.fargo.automation.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.store.OutboxMessageRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OutboxBackoffTest extends OutboxIntegrationTest {

    @Autowired
    private OutboxMessageRepository outbox;

    @Autowired
    private AppProperties properties;

    @Test
    void failedMessagesBackOffAndEndFailedAfterMaxAttempts() {
        Instant start = Instant.parse("2026-10-04T12:00:00Z");
        MutableClock clock = new MutableClock(start);
        AtomicInteger calls = new AtomicInteger();
        OutboxRelay relay = new OutboxRelay(mongoTemplate(), clock, properties.outbox(), message -> {
            calls.incrementAndGet();
            throw new IllegalStateException("push down");
        });
        outbox.save(new OutboxMessage(
                "msg_fail",
                OutboxDestination.PUSH,
                "cust_1",
                Map.of("body", "hello"),
                "pending",
                0,
                start,
                null,
                null,
                null,
                start,
                start,
                1));

        int maxAttempts = properties.outbox().maxAttempts();
        Duration previous = Duration.ZERO;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Instant before = clock.instant();
            relay.dispatchDue();
            OutboxMessage stored = outbox.findById("msg_fail").orElseThrow();
            assertThat(stored.attempts()).isEqualTo(attempt);
            assertThat(stored.lockedBy()).isNull();
            assertThat(stored.lockedUntil()).isNull();
            assertThat(stored.sentAt()).isNull();
            if (attempt == maxAttempts) {
                assertThat(stored.status()).isEqualTo("failed");
                break;
            }
            assertThat(stored.status()).isEqualTo("pending");
            Duration delay = Duration.between(before, stored.nextAttemptAt());
            if (previous.isZero()) {
                assertThat(delay).isEqualTo(Duration.ofSeconds(1));
            } else if (previous.multipliedBy(2).compareTo(Duration.ofMinutes(5)) <= 0) {
                assertThat(delay).isEqualTo(previous.multipliedBy(2));
            } else {
                assertThat(delay).isEqualTo(Duration.ofMinutes(5));
            }
            previous = delay;
            clock.set(stored.nextAttemptAt());
        }

        int afterFailure = calls.get();
        assertThat(afterFailure).isEqualTo(maxAttempts);
        relay.dispatchDue();
        assertThat(calls).hasValue(afterFailure);
        assertThat(outbox.findById("msg_fail").orElseThrow().status()).isEqualTo("failed");
    }
}
