package com.wf.fargo.automation.standalone;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Profile({"standalone", "test"})
public class DevAuthFilter extends OncePerRequestFilter {

    public static final String CUSTOMER_ID_HEADER = "X-Dev-Customer-Id";
    public static final String CALLER_HEADER = "X-Dev-Caller";
    public static final String CUSTOMER_ID_ATTRIBUTE = "com.wf.fargo.automation.devCustomerId";
    public static final String CALLER_ATTRIBUTE = "com.wf.fargo.automation.devCaller";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        copyHeader(request, CUSTOMER_ID_HEADER, CUSTOMER_ID_ATTRIBUTE);
        copyHeader(request, CALLER_HEADER, CALLER_ATTRIBUTE);
        filterChain.doFilter(request, response);
    }

    private static void copyHeader(HttpServletRequest request, String header, String attribute) {
        if (request.getAttribute(attribute) != null) {
            return;
        }
        String value = request.getHeader(header);
        if (value != null) {
            request.setAttribute(attribute, value);
        }
    }
}
