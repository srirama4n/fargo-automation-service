package com.wf.fargo.automation.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.domain.Approval;
import com.wf.fargo.automation.domain.ApprovalStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ApprovalStoreTest extends EmbeddedMongoTest {

    @Autowired
    private ApprovalStore approvalStore;

    @Autowired
    private ApprovalRepository approvals;

    @Test
    void secondDecisionFindsNothingToDo() {
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        approvals.save(new Approval(
                "apr_1",
                "run_1",
                "agt_1",
                "cust_1",
                "sha256:abc",
                ApprovalStatus.PENDING,
                now.plusSeconds(3600),
                null,
                null,
                null,
                now,
                now,
                1));

        assertThat(approvalStore.decide(
                        "apr_1",
                        ApprovalStatus.PENDING,
                        ApprovalStatus.APPROVED,
                        update -> update.set("stepUpRef", "sup_1").set("decidedAt", now)))
                .isPresent()
                .get()
                .extracting(Approval::status, Approval::stepUpRef)
                .containsExactly(ApprovalStatus.APPROVED, "sup_1");

        assertThat(approvalStore.decide("apr_1", ApprovalStatus.PENDING, ApprovalStatus.DENIED, null))
                .isEmpty();
        assertThat(approvals.findById("apr_1").orElseThrow().status()).isEqualTo(ApprovalStatus.APPROVED);
    }
}
