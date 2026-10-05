package com.wf.fargo.automation.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.store.OutboxMessageRepository;
import com.wf.fargo.automation.store.TransactionRunner;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OutboxWriterTest extends OutboxIntegrationTest {

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private TransactionRunner transactions;

    @Autowired
    private OutboxMessageRepository outbox;

    @Test
    void writeOutsideATransactionIsRejected() {
        assertThatThrownBy(() -> writer.write(OutboxDestination.PUSH, "cust_1", Map.of("body", "hi")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void writeInsideATransactionInsertsAPendingMessageAndRollsBackWithIt() {
        OutboxMessage written = transactions.inTransaction(() -> writer.write(
                OutboxDestination.AGENT_CONTROL, "cust_1", Map.of("op", "remove")));
        assertThat(written.messageId()).startsWith("msg_");
        assertThat(written.messageId().substring(4)).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(written.status()).isEqualTo("pending");
        assertThat(written.attempts()).isZero();
        assertThat(written.destination()).isEqualTo(OutboxDestination.AGENT_CONTROL);
        assertThat(written.key()).isEqualTo("cust_1");
        assertThat(outbox.findById(written.messageId())).isPresent();

        assertThatThrownBy(() -> transactions.inTransaction(() -> {
                    writer.write(OutboxDestination.PUSH, "cust_1", Map.of("body", "nope"));
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
        assertThat(outbox.findAll()).hasSize(1);
    }
}
