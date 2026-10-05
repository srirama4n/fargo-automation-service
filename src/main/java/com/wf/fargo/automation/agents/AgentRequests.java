package com.wf.fargo.automation.agents;

import java.util.List;
import java.util.Map;

public final class AgentRequests {

    private AgentRequests() {}

    public record ConfirmationRequest(String templateId, Integer templateVersion, Map<String, Object> params) {}

    public record ValidateRequest(String templateId, Integer templateVersion, Map<String, Object> params) {}

    public record CreateRequest(
            String templateId,
            Integer templateVersion,
            Map<String, Object> params,
            String confirmationToken,
            String sourceUtterance,
            String sessionId,
            List<String> tools) {}

    public record PatchRequest(String status) {}
}
