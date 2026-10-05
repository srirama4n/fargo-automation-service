package com.wf.fargo.automation.sweeper;

import com.wf.fargo.automation.approvals.ApprovalService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ApprovalExpiryJob {

    private final ApprovalService approvals;

    public ApprovalExpiryJob(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @Scheduled(cron = "${automation.sweeper.approvalExpiryCron}")
    @SchedulerLock(name = "approvalExpiry", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void sweep() {
        expireDue();
    }

    public int expireDue() {
        return approvals.expireDue();
    }
}
