package com.wf.fargo.automation.admin;

import com.wf.fargo.automation.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class AdminIdentity {

    public static final String ADMIN_ID_ATTRIBUTE = "com.wf.fargo.automation.adminId";

    static final String CALLER_ATTRIBUTE = "com.wf.fargo.automation.devCaller";

    public String require(HttpServletRequest request) {
        Object adminId = request.getAttribute(ADMIN_ID_ATTRIBUTE);
        if (adminId instanceof String id && !id.isBlank()) {
            return id;
        }
        if ("admin".equals(request.getAttribute(CALLER_ATTRIBUTE))) {
            return "admin";
        }
        throw new ApiException(401, "unauthorized", "Sign in is required.");
    }
}
