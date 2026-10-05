package com.wf.fargo.automation.outbox;

import com.wf.fargo.automation.EmbeddedMongoTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

abstract class OutboxIntegrationTest extends EmbeddedMongoTest {

    @Autowired
    private OutboxRelay sharedRelay;

    @BeforeEach
    void stopSharedRelay() {
        sharedRelay.stop();
    }
}
