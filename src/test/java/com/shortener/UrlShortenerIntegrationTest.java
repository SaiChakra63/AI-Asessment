package com.shortener;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class UrlShortenerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.rate-limit.capacity", () -> "5");
        registry.add("app.rate-limit.window", () -> "1m");
        registry.add("app.visitor-hash-salt", () -> "integration-test-secret");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void createsRedirectsReportsStatsAndDeactivatesUrl() throws Exception {
        mockMvc.perform(post("/api/v1/urls/shorten")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.10");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "originalUrl": "https://example.com/assessment",
                                  "customCode": "abc123"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.short_code").value("abc123"));
        assertNotNull(redisTemplate.opsForValue().get("url:v2:abc123"));

        mockMvc.perform(get("/api/v1/urls/abc123")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.10");
                            return request;
                        })
                        .header("User-Agent", "Mozilla/5.0 (iPhone)")
                        .header("Referer", "https://search.example"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/assessment"));

        mockMvc.perform(get("/api/v1/analytics/urls/abc123/stats")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.10");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_clicks").value(1))
                .andExpect(jsonPath("$.unique_visitors").value(1))
                .andExpect(jsonPath("$.device_breakdown.Mobile").value(1))
                .andExpect(jsonPath("$.location_breakdown.Unknown").value(1));

        mockMvc.perform(delete("/api/v1/urls/abc123")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.10");
                            return request;
                        }))
                .andExpect(status().isNoContent());
        assertNull(redisTemplate.opsForValue().get("url:v2:abc123"));

        mockMvc.perform(get("/api/v1/urls/abc123")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.10");
                            return request;
                        }))
                .andExpect(status().isNotFound());
    }

    @Test
    void enforcesRateLimitAcrossRequestsStoredInRedis() throws Exception {
        for (int requestNumber = 1; requestNumber <= 5; requestNumber++) {
            mockMvc.perform(get("/api/v1/urls/missing")
                            .with(request -> {
                                request.setRemoteAddr("198.51.100.20");
                                return request;
                            }))
                    .andExpect(status().isNotFound());
        }

        mockMvc.perform(get("/api/v1/urls/missing")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.20");
                            return request;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error_code").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void reportsDatabaseAndRedisHealth() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.redis").value("UP"));
    }
}
