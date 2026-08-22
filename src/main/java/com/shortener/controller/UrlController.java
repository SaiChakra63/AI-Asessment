package com.shortener.controller;

import com.shortener.dto.ShortenUrlRequest;
import com.shortener.dto.ShortenUrlResponse;
import com.shortener.service.UrlAnalyticsService;
import com.shortener.service.RequestMetadataExtractor;
import com.shortener.service.UrlShorteningService;
import com.shortener.security.AuthenticatedClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/urls")
public class UrlController {

    private final UrlShorteningService urlShorteningService;
    private final UrlAnalyticsService urlAnalyticsService;
    private final RequestMetadataExtractor metadataExtractor;
    private final MeterRegistry meterRegistry;
    private final Timer redirectTimer;
    private final String shortBaseUrl;

    public UrlController(
            UrlShorteningService urlShorteningService,
            UrlAnalyticsService urlAnalyticsService,
            RequestMetadataExtractor metadataExtractor,
            MeterRegistry meterRegistry,
            @Value("${app.short-base-url}") String shortBaseUrl
    ) {
        this.urlShorteningService = urlShorteningService;
        this.urlAnalyticsService = urlAnalyticsService;
        this.metadataExtractor = metadataExtractor;
        this.meterRegistry = meterRegistry;
        this.redirectTimer = Timer.builder("url_redirect_duration")
                .description("End-to-end redirect request duration")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.shortBaseUrl = shortBaseUrl.replaceAll("/+$", "");
    }

    @PostMapping("/shorten")
    @Operation(summary = "Create a shortened URL")
    @SecurityRequirement(name = "ApiKeyAuth")
    public ResponseEntity<ShortenUrlResponse> shorten(
            @Valid @RequestBody ShortenUrlRequest request,
            @AuthenticationPrincipal AuthenticatedClient actor
    ) {
        var mapping = urlShorteningService.createShortenedUrl(request, actor);
        var response = new ShortenUrlResponse(
                mapping.getShortCode(),
                shortBaseUrl + "/" + mapping.getShortCode(),
                mapping.getOriginalUrl(),
                mapping.getCreatedAt()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{shortCode}")
    @Operation(summary = "Redirect to the original URL")
    public ResponseEntity<Void> redirect(
            @PathVariable String shortCode,
            HttpServletRequest request
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var resolvedUrl = urlShorteningService.resolveActiveUrl(shortCode);
            urlAnalyticsService.recordAccess(resolvedUrl, metadataExtractor.extract(request));
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(resolvedUrl.originalUrl()))
                    .build();
        } finally {
            sample.stop(redirectTimer);
        }
    }

    @DeleteMapping("/{shortCode}")
    @Operation(summary = "Deactivate a shortened URL")
    @SecurityRequirement(name = "ApiKeyAuth")
    public ResponseEntity<Void> delete(
            @PathVariable String shortCode,
            @AuthenticationPrincipal AuthenticatedClient actor
    ) {
        urlShorteningService.deactivate(shortCode, actor);
        return ResponseEntity.noContent().build();
    }
}
