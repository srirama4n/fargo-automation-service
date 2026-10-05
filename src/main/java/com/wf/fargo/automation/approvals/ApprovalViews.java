package com.wf.fargo.automation.approvals;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ApprovalViews {

    private ApprovalViews() {}

    public record ApprovalView(
            String approvalId,
            String status,
            Instant expiresAt,
            AgentName agent,
            Map<String, Object> trigger,
            PreviewView preview) {}

    public record AgentName(String name) {}

    public record PreviewView(String effect, List<String> warnings, Map<String, Object> data) {}
}
