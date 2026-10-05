package com.wf.fargo.automation.config;

import com.wf.fargo.automation.admin.AdminIdentity;
import com.wf.fargo.automation.common.ApiException;
import com.wf.fargo.automation.common.CallerContext;
import com.wf.fargo.automation.config.AccessTokens.Identity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public class CallerAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final AccessTokens tokens;

    public CallerAuthenticationFilter(AccessTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!header.startsWith(BEARER) || header.length() == BEARER.length()) {
            reject(response);
            return;
        }
        try {
            Identity identity = tokens.verify(header.substring(BEARER.length()).trim());
            if (identity.adminId() != null) {
                request.setAttribute(AdminIdentity.ADMIN_ID_ATTRIBUTE, identity.adminId());
            }
            if (identity.customerId() != null) {
                request.setAttribute(CallerContext.CUSTOMER_ID_ATTRIBUTE, identity.customerId());
            }
        } catch (ApiException ex) {
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter()
                .write(
                        "{\"type\":\"https://errors.fargo/automation/unauthorized\",\"title\":\"Unauthorized\",\"status\":401,\"code\":\"unauthorized\",\"detail\":\"Sign in is required.\"}");
    }
}
