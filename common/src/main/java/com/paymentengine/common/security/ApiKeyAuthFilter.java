package com.payflow.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Shared across every service's own Spring context (registered explicitly
 * via each service's own FilterRegistrationBean, since component scanning
 * never crosses from com.payflow.common into a service's own base package).
 * Deliberately simple -- a single static key checked against one header --
 * since the point is demonstrating the auth-boundary concept, not building
 * a credential-management system.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-API-Key";

    private final String expectedApiKey;
    private final List<String> exemptPathPrefixes;

    public ApiKeyAuthFilter(String expectedApiKey, List<String> exemptPathPrefixes) {
        this.expectedApiKey = expectedApiKey;
        this.exemptPathPrefixes = exemptPathPrefixes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // CORS preflight requests never carry custom headers like X-API-Key --
        // that's the whole point of a preflight, it's the browser asking
        // permission to send one. Blocking OPTIONS here doesn't leak anything
        // (no response body, just the CORS policy), but it does break every
        // actual cross-origin request behind it if left unexempted.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                || isExempt(request.getRequestURI())
                || expectedApiKey.equals(request.getHeader(HEADER_NAME))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"missing or invalid " + HEADER_NAME + " header\"}");
    }

    private boolean isExempt(String requestUri) {
        return exemptPathPrefixes.stream().anyMatch(requestUri::startsWith);
    }
}
