package com.shortener;

import com.shortener.model.ApiClient;
import com.shortener.repository.ApiClientRepository;
import com.shortener.security.ApiKeyHasher;
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

    private static final String API_KEY = "integration-test-api-key-0123456789";
    private static final String OTHER_API_KEY = "integration-test-other-key-0123456789";

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
        registry.add("app.analytics.visitor-hash-salt",
                () -> "integration-test-visitor-secret-0123456789");
        registry.add("app.security.api-key.pepper", () -> "integration-test-pepper-0123456789");
        registry.add("app.security.bootstrap.client-id", () -> "integration-client");
        registry.add("app.security.bootstrap.api-key", () -> API_KEY);
        registry.add("app.security.bootstrap.authorities",
                () -> "URL_WRITE,ANALYTICS_READ,OPS_READ");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ApiClientRepository apiClientRepository;

    @Autowired
    private ApiKeyHasher apiKeyHasher;

    @Test
    void createsRedirectsReportsStatsAndDeactivatesUrl() throws Exception {
        mockMvc.perform(post("/api/v1/urls/shorten")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.10");
                            return request;
                        })
                        .header("X-API-Key", API_KEY)
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
                        })
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_clicks").value(1))
                .andExpect(jsonPath("$.unique_visitors").value(1))
                .andExpect(jsonPath("$.device_breakdown.Mobile").value(1))
                .andExpect(jsonPath("$.location_breakdown.Unknown").value(1));

        mockMvc.perform(delete("/api/v1/urls/abc123")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.10");
                            return request;
                        })
                        .header("X-API-Key", API_KEY))
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
        mockMvc.perform(get("/api/v1/health")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.70");
                            return request;
                        })
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.redis").value("UP"));
    }

    @Test
    void protectsManagementEndpointsButKeepsRedirectsPublic() throws Exception {
        mockMvc.perform(post("/api/v1/urls/shorten")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.30");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://example.com/protected"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(get("/api/v1/urls/does-not-exist")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.30");
                            return request;
                        }))
                .andExpect(status().isNotFound());
    }

    @Test
    void enforcesRolesAndOwnershipEvenWhenStatisticsAreCached() throws Exception {
        apiClientRepository.saveAndFlush(ApiClient.builder()
                .clientId("other-integration-client")
                .displayName("Other integration client")
                .apiKeyDigest(apiKeyHasher.digest(OTHER_API_KEY))
                .authorities("URL_WRITE,ANALYTICS_READ")
                .active(true)
                .build());

        mockMvc.perform(post("/api/v1/urls/shorten")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.60");
                            return request;
                        })
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "originalUrl": "https://example.com/owned",
                                  "customCode": "owned1"
                                }
                                """))
                .andExpect(status().isCreated());

        // The owner populates the statistics cache first.
        mockMvc.perform(get("/api/v1/analytics/urls/owned1/stats")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.60");
                            return request;
                        })
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk());
        assertNotNull(redisTemplate.opsForValue().get("stats:v2:owned1"));

        // A valid key for another client cannot use that cached value or delete the URL.
        mockMvc.perform(get("/api/v1/analytics/urls/owned1/stats")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.60");
                            return request;
                        })
                        .header("X-API-Key", OTHER_API_KEY))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/urls/owned1")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.60");
                            return request;
                        })
                        .header("X-API-Key", OTHER_API_KEY))
                .andExpect(status().isNotFound());

        // The second client has no OPS_READ role, so role authorization returns 403.
        mockMvc.perform(get("/api/v1/health")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.60");
                            return request;
                        })
                        .header("X-API-Key", OTHER_API_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("ACCESS_DENIED"));

        ApiClient otherClient = apiClientRepository.findById("other-integration-client").orElseThrow();
        otherClient.setActive(false);
        apiClientRepository.saveAndFlush(otherClient);
        mockMvc.perform(get("/api/v1/analytics/urls/owned1/stats")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.61");
                            return request;
                        })
                        .header("X-API-Key", OTHER_API_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("INVALID_API_KEY"));
    }

    @Test
    void ignoresInvalidCredentialsOnPublicRedirectEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/urls/does-not-exist-public")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.40");
                            return request;
                        })
                        .header("X-API-Key", "stale-or-invalid-key"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error_code").value("URL_NOT_FOUND"));
    }

    @Test
    void rateLimitsInvalidApiKeysBeforeAuthenticationDatabaseWork() throws Exception {
        for (int requestNumber = 1; requestNumber <= 5; requestNumber++) {
            mockMvc.perform(post("/api/v1/urls/shorten")
                            .with(request -> {
                                request.setRemoteAddr("198.51.100.50");
                                return request;
                            })
                            .header("X-API-Key", "invalid-key-" + requestNumber)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"originalUrl\":\"https://example.com/auth-probe\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/urls/shorten")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.50");
                            return request;
                        })
                        .header("X-API-Key", "invalid-key-6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalUrl\":\"https://example.com/auth-probe\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error_code").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void returnsUnsupportedMediaTypeForPlainTextBody() throws Exception {
        mockMvc.perform(post("/api/v1/urls/shorten")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.80");
                            return request;
                        })
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("https://example.com/not-json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error_code").value("UNSUPPORTED_MEDIA_TYPE"));
    }
}
