package com.wf.fargo.automation.common;

import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;

public final class CallerContext {

    public static final String CUSTOMER_ID_ATTRIBUTE = "com.wf.fargo.automation.devCustomerId";
    public static final String FARGO_CUSTOMER_HEADER = "X-Fargo-Customer-Id";

    private static final String CALLER_ATTRIBUTE = "com.wf.fargo.automation.devCaller";
    private static final String CLIENT_CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private CallerContext() {}

    public static String requireCustomerId(HttpServletRequest request) {
        if (mcp(request)) {
            String header = request.getHeader(FARGO_CUSTOMER_HEADER);
            if (header != null && !header.isBlank()) {
                return header.trim();
            }
        }
        Object value = request.getAttribute(CUSTOMER_ID_ATTRIBUTE);
        if (value instanceof String customerId && !customerId.isBlank()) {
            return customerId;
        }
        throw new ApiException(401, "unauthorized", "Sign in is required.");
    }

    private static boolean mcp(HttpServletRequest request) {
        if ("mcp".equals(request.getAttribute(CALLER_ATTRIBUTE))) {
            return true;
        }
        Object certificates = request.getAttribute(CLIENT_CERT_ATTRIBUTE);
        return certificates instanceof X509Certificate[] chain && chain.length > 0 && chain[0] != null;
    }
}
