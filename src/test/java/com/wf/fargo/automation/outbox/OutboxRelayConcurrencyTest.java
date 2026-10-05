package com.wf.fargo.automation.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.store.OutboxMessageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OutboxRelayConcurrencyTest extends OutboxIntegrationTest {

    @Autowired
    private OutboxMessageRepository outbox;

    @Autowired
    private AppProperties properties;

    @Test
    void twoRelaysNeverSendTheSameMessageConcurrently() throws Exception {
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        outbox.save(new OutboxMessage(
                "msg_race",
                OutboxDestination.PUSH,
                "cust_1",
                Map.of("body", "hello"),
                "pending",
                0,
                now,
                null,
                null,
                null,
                now,
                now,
                1));
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();
        var delivery = (java.util.function.Consumer<OutboxMessage>) message -> {
            sends.incrementAndGet();
            entered.countDown();
            try {
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        };
        OutboxRelay first = new OutboxRelay(mongoTemplate(), clock, properties.outbox(), delivery);
        OutboxRelay second = new OutboxRelay(mongoTemplate(), clock, properties.outbox(), delivery);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> left = pool.submit(first::dispatchDue);
            Future<?> right = pool.submit(second::dispatchDue);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!(left.isDone() ^ right.isDone())) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("expected exactly one relay to finish while the other holds the lease");
                }
                Thread.sleep(10);
            }
            assertThat(sends).hasValue(1);
            release.countDown();
            left.get(10, TimeUnit.SECONDS);
            right.get(10, TimeUnit.SECONDS);
            assertThat(sends).hasValue(1);
            assertThat(outbox.findById("msg_race").orElseThrow().status()).isEqualTo("sent");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
