package com.wf.fargo.automation.agents;

import com.wf.fargo.automation.agents.AgentRequests.CreateRequest;
import com.wf.fargo.automation.agents.AgentRequests.ValidateRequest;
import com.wf.fargo.automation.agents.AgentViews.CreatedAgent;
import com.wf.fargo.automation.agents.AgentViews.TemplateList;
import com.wf.fargo.automation.agents.AgentViews.TemplateView;
import com.wf.fargo.automation.agents.AgentViews.ValidationView;
import com.wf.fargo.automation.common.ApiException;
import com.wf.fargo.automation.common.CallerContext;
import com.wf.fargo.automation.common.RawAccountNumbers;
import com.wf.fargo.automation.domain.AgentTemplate;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentToolsController {

    private final TemplateService templates;
    private final PolicyChecker policyChecker;
    private final AgentService agents;

    public AgentToolsController(TemplateService templates, PolicyChecker policyChecker, AgentService agents) {
        this.templates = templates;
        this.policyChecker = policyChecker;
        this.agents = agents;
    }

    @GetMapping("/v1/templates")
    public TemplateList list(HttpServletRequest request, @RequestParam(required = false) String status) {
        CallerContext.requireCustomerId(request);
        List<TemplateView> views = templates.list(status).stream().map(AgentToolsController::view).toList();
        return new TemplateList(views);
    }

    @PostMapping("/v1/agents/validate")
    public ValidationView validate(HttpServletRequest request, @RequestBody ValidateRequest body) {
        String customerId = CallerContext.requireCustomerId(request);
        requireDraft(body.templateId(), body.templateVersion(), body.params());
        RawAccountNumbers.reject(body.params());
        PolicyChecker.PolicyCheck check =
                policyChecker.check(customerId, body.templateId(), body.templateVersion(), body.params());
        if (!check.errors().isEmpty()) {
            return new ValidationView(false, null, null, check.errors());
        }
        return new ValidationView(true, check.draftHash(), check.effectText(), List.of());
    }

    @PostMapping("/v1/agents")
    public ResponseEntity<CreatedAgent> create(HttpServletRequest request, @RequestBody CreateRequest body) {
        String customerId = CallerContext.requireCustomerId(request);
        RawAccountNumbers.reject(body.params());
        CreatedAgent created = agents.create(
                customerId,
                body.templateId(),
                body.templateVersion(),
                body.params(),
                body.confirmationToken(),
                body.sourceUtterance(),
                body.sessionId());
        return ResponseEntity.status(201).body(created);
    }

    private static void requireDraft(String templateId, Integer templateVersion, Object params) {
        if (templateId == null || templateId.isBlank() || templateVersion == null || params == null) {
            throw new ApiException(400, "validation_error", "templateId, templateVersion, and params are required.");
        }
    }

    private static TemplateView view(AgentTemplate template) {
        String mode = template.mode() == null ? null : template.mode().name().toLowerCase(Locale.ROOT);
        return new TemplateView(
                template.templateId(),
                template.version(),
                template.name(),
                template.description(),
                mode,
                template.paramSchema(),
                template.limits(),
                template.effectText());
    }
}
