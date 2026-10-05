package com.wf.fargo.automation.grants;

import com.wf.fargo.automation.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import org.springframework.stereotype.Component;

@Component
public class McpIdentity {

    static final String CALLER_ATTRIBUTE = "com.wf.fargo.automation.devCaller";
    private static final String CLIENT_CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    public void require(HttpServletRequest request) {
        if ("mcp".equals(request.getAttribute(CALLER_ATTRIBUTE))) {
            return;
        }
        Object certificates = request.getAttribute(CLIENT_CERT_ATTRIBUTE);
        if (certificates instanceof X509Certificate[] chain && chain.length > 0 && chain[0] != null) {
            return;
        }
        throw new ApiException(401, "unauthorized", "An MCP client certificate is required.");
    }
}
