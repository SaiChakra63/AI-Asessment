package com.shortener.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortener.dto.ErrorResponse;
import com.shortener.service.RateLimitDecision;
import com.shortener.service.RateLimitService;
import com.shortener.service.RequestMetadataExtractor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * Applies the distributed IP rate limit before API-key authentication. Keeping
 * this in the servlet-security filter chain prevents invalid-key traffic from
 * bypassing the limiter and repeatedly querying the API-client table.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final RequestMetadataExtractor metadataExtractor;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
            RateLimitService rateLimitService,
            RequestMetadataExtractor metadataExtractor,
            ObjectMapper objectMapper
    ) {
        this.rateLimitService = rateLimitService;
        this.metadataExtractor = metadataExtractor;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !applicationPath(request).startsWith("/api/v1/");
    }

    private String applicationPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank()
                && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        RateLimitDecision decision = rateLimitService.evaluate(metadataExtractor.clientIp(request));
        if (decision.remaining() >= 0) {
            response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        }
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        int status = HttpStatus.TOO_MANY_REQUESTS.value();
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(
                Instant.now(),
                status,
                "RATE_LIMIT_EXCEEDED",
                "Request limit exceeded; retry after the indicated delay",
                request.getRequestURI(),
                Map.of("retryAfterSeconds", Long.toString(decision.retryAfterSeconds()))
        ));
    }
}
