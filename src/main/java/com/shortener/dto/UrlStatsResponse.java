package com.shortener.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.Map;

public record UrlStatsResponse(
        @JsonProperty("short_code") String shortCode,
        @JsonProperty("original_url") String originalUrl,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("total_clicks") long totalClicks,
        @JsonProperty("unique_visitors") long uniqueVisitors,
        @JsonProperty("last_accessed") LocalDateTime lastAccessed,
        @JsonProperty("device_breakdown") Map<String, Long> deviceBreakdown,
        @JsonProperty("referrer_breakdown") Map<String, Long> referrerBreakdown,
        @JsonProperty("location_breakdown") Map<String, Long> locationBreakdown
) {
}
