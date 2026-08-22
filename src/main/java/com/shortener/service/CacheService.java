package com.shortener.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortener.dto.UrlStatsResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Best-effort Redis cache for redirects and statistics. Cache failures fall
 * back to PostgreSQL and are surfaced through the cache-failure metric.
 */
@Service
public class CacheService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheService.class);
    private static final String URL_PREFIX = "url:v2:";
    private static final String STATS_PREFIX = "stats:v2:";

    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;
    private final Duration urlTtl;
    private final Duration statsTtl;
    private final Counter urlHits;
    private final Counter urlMisses;
    private final Counter failures;

    public CacheService(
            StringRedisTemplate redisTemplate,
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            @Value("${app.cache.url-ttl:24h}") Duration urlTtl,
            @Value("${app.cache.stats-ttl:1h}") Duration statsTtl,
            MeterRegistry meterRegistry
    ) {
        if (urlTtl == null || urlTtl.isZero() || urlTtl.isNegative()
                || statsTtl == null || statsTtl.isZero() || statsTtl.isNegative()) {
            throw new IllegalArgumentException("Cache TTL values must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
        this.urlTtl = urlTtl;
        this.statsTtl = statsTtl;
        this.urlHits = meterRegistry.counter("url_cache_requests_total", "result", "hit");
        this.urlMisses = meterRegistry.counter("url_cache_requests_total", "result", "miss");
        this.failures = meterRegistry.counter("url_cache_failures_total");
    }

    public Optional<ResolvedUrl> getUrl(String shortCode) {
        try {
            String value = redisTemplate.opsForValue().get(URL_PREFIX + shortCode);
            if (value == null) {
                urlMisses.increment();
                return Optional.empty();
            }
            ResolvedUrl resolvedUrl = objectMapper.readValue(value, ResolvedUrl.class);
            urlHits.increment();
            return Optional.of(resolvedUrl);
        } catch (RuntimeException | JsonProcessingException exception) {
            recordFailure("read URL", shortCode, exception);
            return Optional.empty();
        }
    }

    public void putUrl(ResolvedUrl resolvedUrl) {
        try {
            redisTemplate.opsForValue().set(
                    URL_PREFIX + resolvedUrl.shortCode(),
                    objectMapper.writeValueAsString(resolvedUrl),
                    urlTtl
            );
        } catch (RuntimeException | JsonProcessingException exception) {
            recordFailure("write URL", resolvedUrl.shortCode(), exception);
        }
    }

    public Optional<UrlStatsResponse> getStats(String shortCode) {
        try {
            String value = redisTemplate.opsForValue().get(STATS_PREFIX + shortCode);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, UrlStatsResponse.class));
        } catch (RuntimeException | JsonProcessingException exception) {
            recordFailure("read statistics", shortCode, exception);
            return Optional.empty();
        }
    }

    public void putStats(String shortCode, UrlStatsResponse response) {
        try {
            redisTemplate.opsForValue().set(
                    STATS_PREFIX + shortCode,
                    objectMapper.writeValueAsString(response),
                    statsTtl
            );
        } catch (RuntimeException | JsonProcessingException exception) {
            recordFailure("write statistics", shortCode, exception);
        }
    }

    public void invalidate(String shortCode) {
        try {
            redisTemplate.delete(java.util.List.of(URL_PREFIX + shortCode, STATS_PREFIX + shortCode));
        } catch (RuntimeException exception) {
            recordFailure("invalidate cache", shortCode, exception);
        }
    }

    public void invalidateStats(String shortCode) {
        try {
            redisTemplate.delete(STATS_PREFIX + shortCode);
        } catch (RuntimeException exception) {
            recordFailure("invalidate statistics", shortCode, exception);
        }
    }

    public boolean isRedisHealthy() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping());
        } catch (RuntimeException exception) {
            failures.increment();
            LOGGER.warn("Redis health check failed: {}", exception.getMessage());
            return false;
        }
    }

    private void recordFailure(String operation, String shortCode, Exception exception) {
        failures.increment();
        LOGGER.warn("Unable to {} for short code {}. Falling back safely: {}",
                operation, shortCode, exception.getMessage());
    }
}
