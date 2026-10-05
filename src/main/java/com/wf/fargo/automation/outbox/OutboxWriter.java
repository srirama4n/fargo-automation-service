package com.wf.fargo.automation.outbox;

import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.store.OutboxMessageRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class OutboxWriter {

    private final OutboxMessageRepository outbox;
    private final Clock clock;

    public OutboxWriter(OutboxMessageRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    public OutboxMessage write(OutboxDestination destination, String key, Map<String, Object> payload) {
        return write(destination, key, payload, clock.instant());
    }

    public OutboxMessage write(
            OutboxDestination destination, String key, Map<String, Object> payload, Instant nextAttemptAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OutboxWriter.write must run inside a transaction");
        }
        if (destination == null || key == null || payload == null) {
            throw new IllegalArgumentException("destination, key, and payload are required");
        }
        Instant now = clock.instant();
        OutboxMessage message = new OutboxMessage(
                "msg_" + Ulids.next(clock.millis()),
                destination,
                key,
                payload,
                "pending",
                0,
                nextAttemptAt == null ? now : nextAttemptAt,
                null,
                null,
                null,
                now,
                now,
                1);
        return outbox.save(message);
    }
}
