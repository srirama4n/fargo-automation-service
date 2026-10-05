package com.wf.fargo.automation.agents;

import com.wf.fargo.automation.agents.AgentRequests.ConfirmationRequest;
import com.wf.fargo.automation.agents.AgentRequests.PatchRequest;
import com.wf.fargo.automation.agents.AgentViews.AgentDetail;
import com.wf.fargo.automation.agents.AgentViews.AgentList;
import com.wf.fargo.automation.agents.AgentViews.ConfirmationView;
import com.wf.fargo.automation.agents.AgentViews.StatusChange;
import com.wf.fargo.automation.common.ApiException;
import com.wf.fargo.automation.common.CallerContext;
import com.wf.fargo.automation.common.RawAccountNumbers;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentController {

    private final ConfirmationService confirmations;
    private final AgentService agents;

    public AgentController(ConfirmationService confirmations, AgentService agents) {
        this.confirmations = confirmations;
        this.agents = agents;
    }

    @PostMapping("/v1/confirmations")
    public ResponseEntity<ConfirmationView> confirm(HttpServletRequest request, @RequestBody ConfirmationRequest body) {
        String customerId = CallerContext.requireCustomerId(request);
        if (body.templateId() == null || body.templateId().isBlank() || body.templateVersion() == null || body.params() == null) {
            throw new ApiException(400, "validation_error", "templateId, templateVersion, and params are required.");
        }
        RawAccountNumbers.reject(body.params());
        ConfirmationService.Issued issued =
                confirmations.issue(customerId, body.templateId(), body.templateVersion(), body.params());
        return ResponseEntity.status(201).body(new ConfirmationView(issued.confirmationToken(), issued.expiresAt()));
    }

    @GetMapping("/v1/customers/{customerId}/agents")
    public AgentList list(
            HttpServletRequest request,
            @PathVariable String customerId,
            @RequestParam(required = false) String status) {
        return new AgentList(agents.list(CallerContext.requireCustomerId(request), customerId, status));
    }

    @GetMapping("/v1/agents/{agentId}")
    public ResponseEntity<AgentDetail> detail(HttpServletRequest request, @PathVariable String agentId) {
        String customerId = CallerContext.requireCustomerId(request);
        AgentDetail detail = agents.detail(customerId, agentId);
        return ResponseEntity.ok().eTag(Long.toString(agents.version(customerId, agentId))).body(detail);
    }

    @PatchMapping("/v1/agents/{agentId}")
    public ResponseEntity<Map<String, String>> patch(
            HttpServletRequest request,
            @PathVariable String agentId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody PatchRequest body) {
        StatusChange change = agents.updateStatus(
                CallerContext.requireCustomerId(request), agentId, ifMatch, body.status());
        return ResponseEntity.ok().eTag(Long.toString(change.version())).body(Map.of("status", change.status()));
    }

    @DeleteMapping("/v1/agents/{agentId}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable String agentId) {
        agents.delete(CallerContext.requireCustomerId(request), agentId);
        return ResponseEntity.noContent().build();
    }
}
