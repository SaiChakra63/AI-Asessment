package com.shortener.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortener.dto.ErrorResponse;
import com.shortener.service.RateLimitDecision;
import com.shortener.service.RateLimitService;
import com.shortener.service.RequestMetadataExtractor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.Map;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;
    private final RequestMetadataExtractor metadataExtractor;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(
            RateLimitService rateLimitService,
            RequestMetadataExtractor metadataExtractor,
            ObjectMapper objectMapper
    ) {
        this.rateLimitService = rateLimitService;
        this.metadataExtractor = metadataExtractor;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) throws Exception {
        RateLimitDecision decision = rateLimitService.evaluate(metadataExtractor.clientIp(request));
        if (decision.remaining() >= 0) {
            response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        }
        if (decision.allowed()) {
            return true;
        }

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(
                Instant.now(),
                429,
                "RATE_LIMIT_EXCEEDED",
                "Request limit exceeded; retry after the indicated delay",
                request.getRequestURI(),
                Map.of("retryAfterSeconds", Long.toString(decision.retryAfterSeconds()))
        ));
        return false;
    }
}
