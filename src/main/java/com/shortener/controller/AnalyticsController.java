package com.shortener.controller;

import com.shortener.dto.UrlStatsResponse;
import com.shortener.security.AuthenticatedClient;
import com.shortener.service.UrlAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final UrlAnalyticsService urlAnalyticsService;

    public AnalyticsController(UrlAnalyticsService urlAnalyticsService) {
        this.urlAnalyticsService = urlAnalyticsService;
    }

    @GetMapping("/urls/{shortCode}/stats")
    @Operation(summary = "Get click statistics for a shortened URL")
    @SecurityRequirement(name = "ApiKeyAuth")
    public ResponseEntity<UrlStatsResponse> getStats(
            @PathVariable String shortCode,
            @AuthenticationPrincipal AuthenticatedClient actor
    ) {
        return ResponseEntity.ok(urlAnalyticsService.getStats(shortCode, actor));
    }
}
