package com.shortener.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(
        Instant timestamp,
        int status,
        @JsonProperty("error_code") String errorCode,
        String message,
        String path,
        Map<String, String> details
) {
}
