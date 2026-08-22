package com.shortener.service;

public record ResolvedUrl(Long mappingId, String shortCode, String originalUrl) {
}
