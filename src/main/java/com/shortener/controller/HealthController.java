package com.shortener.controller;

import com.shortener.service.CacheService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final CacheService cacheService;
    private final JdbcTemplate jdbcTemplate;
    private final boolean cacheRequired;

    public HealthController(
            CacheService cacheService,
            JdbcTemplate jdbcTemplate,
            @Value("${app.cache.required:false}") boolean cacheRequired
    ) {
        this.cacheService = cacheService;
        this.jdbcTemplate = jdbcTemplate;
        this.cacheRequired = cacheRequired;
    }

    @GetMapping
    @Operation(summary = "Get application and Redis health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean redisHealthy = cacheService.isRedisHealthy();
        boolean databaseHealthy = isDatabaseHealthy();
        Map<String, Object> result = new LinkedHashMap<>();
        String overallStatus = !databaseHealthy
                ? "DOWN"
                : redisHealthy ? "UP" : "DEGRADED";
        result.put("status", overallStatus);
        result.put("database", databaseHealthy ? "UP" : "DOWN");
        result.put("redis", redisHealthy ? "UP" : "DOWN");
        result.put("cacheRequired", cacheRequired);

        HttpStatus status = !databaseHealthy || (!redisHealthy && cacheRequired)
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(result);
    }

    private boolean isDatabaseHealthy() {
        try {
            return Integer.valueOf(1).equals(
                    jdbcTemplate.queryForObject("SELECT 1", Integer.class));
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
