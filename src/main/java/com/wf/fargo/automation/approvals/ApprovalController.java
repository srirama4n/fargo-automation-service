package com.wf.fargo.automation.approvals;

import com.wf.fargo.automation.approvals.ApprovalViews.ApprovalView;
import com.wf.fargo.automation.common.CallerContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApprovalController {

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping("/v1/approvals/{approvalId}")
    public ApprovalView get(HttpServletRequest request, @PathVariable String approvalId) {
        return approvals.get(CallerContext.requireCustomerId(request), approvalId);
    }

    @PostMapping("/v1/approvals/{approvalId}/approve")
    public Map<String, String> approve(
            HttpServletRequest request,
            @PathVariable String approvalId,
            @RequestHeader(value = "X-StepUp-Token", required = false) String token) {
        ApprovalService.Decision decision =
                approvals.approve(CallerContext.requireCustomerId(request), approvalId, token);
        return Map.of("status", decision.status(), "runState", decision.runState());
    }

    @PostMapping("/v1/approvals/{approvalId}/deny")
    public Map<String, String> deny(HttpServletRequest request, @PathVariable String approvalId) {
        return Map.of("status", approvals.deny(CallerContext.requireCustomerId(request), approvalId));
    }
}
