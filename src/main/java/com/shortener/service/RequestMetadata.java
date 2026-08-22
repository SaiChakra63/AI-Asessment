package com.shortener.service;

public record RequestMetadata(
        String clientIp,
        String userAgent,
        String referrer,
        String deviceType,
        String city,
        String country,
        String continent,
        String visitorHash
) {
}
