package com.wf.fargo.automation.admin;

import com.wf.fargo.automation.admin.AdminRequests.PauseAllRequest;
import com.wf.fargo.automation.admin.AdminRequests.PublishTemplateRequest;
import com.wf.fargo.automation.admin.AdminViews.JobView;
import com.wf.fargo.automation.admin.AdminViews.PublishedTemplate;
import com.wf.fargo.automation.agents.TemplateService;
import com.wf.fargo.automation.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {

    private final AdminIdentity admins;
    private final KillSwitchService killSwitch;
    private final TemplateService templates;

    public AdminController(AdminIdentity admins, KillSwitchService killSwitch, TemplateService templates) {
        this.admins = admins;
        this.killSwitch = killSwitch;
        this.templates = templates;
    }

    @PostMapping("/v1/admin/agents/pause-all")
    public JobView pauseAll(HttpServletRequest request, @RequestBody(required = false) PauseAllRequest body) {
        String adminId = admins.require(request);
        if (body == null) {
            throw new ApiException(400, "validation_error", "reason is required.");
        }
        boolean expirePending = body.expirePending() != null && body.expirePending();
        return killSwitch.pauseAll(adminId, body.templateId(), body.reason(), expirePending);
    }

    @GetMapping("/v1/admin/jobs/{jobId}")
    public JobView job(HttpServletRequest request, @PathVariable String jobId) {
        admins.require(request);
        return killSwitch.job(jobId);
    }

    @PostMapping("/v1/admin/templates")
    public ResponseEntity<PublishedTemplate> publish(
            HttpServletRequest request, @RequestBody(required = false) PublishTemplateRequest body) {
        String adminId = admins.require(request);
        if (body == null) {
            throw new ApiException(400, "validation_error", "A template is required.");
        }
        var published = templates.publish(body, adminId);
        return ResponseEntity.status(201)
                .body(new PublishedTemplate(published.templateId(), published.version(), published.status()));
    }
}
