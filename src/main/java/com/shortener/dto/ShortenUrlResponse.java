package com.shortener.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record ShortenUrlResponse(
        @JsonProperty("short_code") String shortCode,
        @JsonProperty("short_url") String shortUrl,
        @JsonProperty("original_url") String originalUrl,
        @JsonProperty("created_at") LocalDateTime createdAt
) {
}
