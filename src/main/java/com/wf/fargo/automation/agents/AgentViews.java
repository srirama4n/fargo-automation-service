package com.wf.fargo.automation.agents;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wf.fargo.automation.domain.AgentTrigger;
import com.wf.fargo.automation.domain.Guards;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AgentViews {

    private AgentViews() {}

    public record CreatedAgent(String agentId, String status, String grantId) {}

    public record StatusChange(String status, long version) {}

    public record AgentSummary(
            String agentId,
            String name,
            String templateId,
            String mode,
            String status,
            String effectText,
            Instant createdAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) LastRun lastRun) {}

    public record LastRun(String state, Instant at) {}

    public record AgentDetail(
            String agentId,
            String name,
            String templateId,
            int templateVersion,
            String mode,
            String status,
            String effectText,
            Map<String, Object> params,
            AgentTrigger trigger,
            Guards guards,
            GrantView grant,
            List<RunView> runs,
            Instant createdAt) {}

    public record GrantView(List<String> allowedTools, List<String> accountScopes) {}

    public record RunView(String runId, String state, Instant createdAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TemplateView(
            String templateId,
            int version,
            String name,
            String description,
            String mode,
            Map<String, Object> paramSchema,
            Object limits,
            String effectText) {}

    public record TemplateList(List<TemplateView> templates) {}

    public record AgentList(List<AgentSummary> agents) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ValidationView(boolean valid, String draftHash, String effectText, List<com.wf.fargo.automation.common.ApiError> errors) {}

    public record ConfirmationView(String confirmationToken, Instant expiresAt) {}
}
