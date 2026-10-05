package com.wf.fargo.automation.grants;

import com.wf.fargo.automation.common.ApiException;
import com.wf.fargo.automation.domain.Grant;
import com.wf.fargo.automation.store.GrantRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GrantLookupController {

    private final McpIdentity mcpIdentity;
    private final GrantRepository grants;

    public GrantLookupController(McpIdentity mcpIdentity, GrantRepository grants) {
        this.mcpIdentity = mcpIdentity;
        this.grants = grants;
    }

    @GetMapping("/v1/grants/{grantId}")
    public GrantResponse get(HttpServletRequest request, @PathVariable String grantId) {
        mcpIdentity.require(request);
        Grant grant = grants.findById(grantId).orElseThrow(() -> new ApiException(404, "not_found", "Not found."));
        return new GrantResponse(
                grant.grantId(),
                grant.agentId(),
                grant.customerId(),
                grant.allowedTools() == null ? List.of() : grant.allowedTools(),
                grant.accountScopes() == null ? List.of() : grant.accountScopes(),
                grant.limits() == null ? Map.of() : grant.limits(),
                grant.revokedAt() != null);
    }

    public record GrantResponse(
            String grantId,
            String agentId,
            String customerId,
            List<String> allowedTools,
            List<String> accountScopes,
            Map<String, Object> limits,
            boolean revoked) {}
}
