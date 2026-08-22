package com.shortener.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Applies a Redis-backed fixed-window limit to a hashed client identifier.
 * The current availability policy is deliberately fail-open when Redis cannot
 * be reached; failures are counted and logged for operational alerting.
 */
@Service
public class RateLimitService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitService.class);
    private static final DefaultRedisScript<List> INCREMENT_SCRIPT = script();

    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;
    private final long capacity;
    private final Duration window;
    private final Counter rejected;
    private final Counter failures;

    public RateLimitService(
            StringRedisTemplate redisTemplate,
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.capacity:100}") long capacity,
            @Value("${app.rate-limit.window:1m}") Duration window,
            MeterRegistry meterRegistry
    ) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Rate-limit capacity must be positive");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate-limit window must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.capacity = capacity;
        this.window = window;
        this.rejected = meterRegistry.counter("url_rate_limit_requests_total", "result", "rejected");
        this.failures = meterRegistry.counter("url_rate_limit_failures_total");
    }

    public RateLimitDecision evaluate(String clientIdentifier) {
        if (!enabled) {
            return RateLimitDecision.failOpen();
        }
        try {
            String key = "rate-limit:v2:" + sha256(clientIdentifier);
            List<?> result = redisTemplate.execute(
                    INCREMENT_SCRIPT,
                    List.of(key),
                    Long.toString(window.toMillis())
            );
            if (result == null || result.size() < 2) {
                throw new IllegalStateException("Redis rate-limit script returned no result");
            }
            long requestCount = ((Number) result.get(0)).longValue();
            long ttlMillis = Math.max(((Number) result.get(1)).longValue(), 0);
            boolean allowed = requestCount <= capacity;
            if (!allowed) {
                rejected.increment();
            }
            return new RateLimitDecision(
                    allowed,
                    Math.max(capacity - requestCount, 0),
                    Math.max(1, (ttlMillis + 999) / 1000)
            );
        } catch (RuntimeException exception) {
            failures.increment();
            LOGGER.warn("Rate limiter unavailable; request allowed by fail-open policy: {}",
                    exception.getMessage());
            return RateLimitDecision.failOpen();
        }
    }

    private static DefaultRedisScript<List> script() {
        String source = "local count = redis.call('INCR', KEYS[1]); "
                + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); end; "
                + "local ttl = redis.call('PTTL', KEYS[1]); return {count, ttl};";
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(List.class);
        return script;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
