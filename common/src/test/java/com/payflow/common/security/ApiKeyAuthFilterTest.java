package com.payflow.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

    private static final String EXPECTED_KEY = "test-key";

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;

    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter(EXPECTED_KEY, List.of("/actuator"));
    }

    @Test
    void allowsARequestWithTheCorrectKey() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/sagas");
        when(request.getHeader(ApiKeyAuthFilter.HEADER_NAME)).thenReturn(EXPECTED_KEY);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void rejectsARequestWithAMissingKey() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/sagas");
        when(request.getHeader(ApiKeyAuthFilter.HEADER_NAME)).thenReturn(null);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void rejectsARequestWithTheWrongKey() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/sagas");
        when(request.getHeader(ApiKeyAuthFilter.HEADER_NAME)).thenReturn("wrong-key");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void letsAnExemptPathThroughWithNoKeyAtAll() throws Exception {
        when(request.getRequestURI()).thenReturn("/actuator/health");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void letsACorsPreflightThroughWithNoKeyAtAll() throws Exception {
        // A browser's OPTIONS preflight never carries custom headers -- that's
        // the whole point of asking permission first. Blocking it here would
        // break CORS for every actual cross-origin request behind it. Method
        // check short-circuits before the URI is even looked at.
        when(request.getMethod()).thenReturn("OPTIONS");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }
}
