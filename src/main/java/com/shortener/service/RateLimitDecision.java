package com.shortener.service;

public record RateLimitDecision(boolean allowed, long remaining, long retryAfterSeconds) {

    public static RateLimitDecision failOpen() {
        return new RateLimitDecision(true, -1, 0);
    }
}
