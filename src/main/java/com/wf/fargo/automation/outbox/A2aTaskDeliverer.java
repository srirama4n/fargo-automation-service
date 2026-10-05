package com.wf.fargo.automation.outbox;

import com.wf.fargo.automation.domain.OutboxMessage;

/**
 * A2A hand-off. Phase 6 supplies the Supervisor dispatcher.
 * The message payload already contains a freshly minted grant token and must not be stored.
 */
public interface A2aTaskDeliverer {

    void deliver(OutboxMessage message);
}
