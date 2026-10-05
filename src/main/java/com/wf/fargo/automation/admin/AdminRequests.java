package com.wf.fargo.automation.admin;

import com.wf.fargo.automation.domain.TemplateAction;
import com.wf.fargo.automation.domain.TemplateLimits;
import com.wf.fargo.automation.domain.TemplateTrigger;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class AdminRequests {

    private AdminRequests() {}

    public record PauseAllRequest(String templateId, String reason, Boolean expirePending) {}

    public record PublishTemplateRequest(
            String templateId,
            Integer version,
            String name,
            String description,
            TemplateTrigger trigger,
            TemplateAction action,
            String mode,
            Map<String, Object> paramSchema,
            TemplateLimits limits,
            List<String> requiredTools,
            Duration approvalTtl,
            String effectText,
            String reviewedBy) {}
}
