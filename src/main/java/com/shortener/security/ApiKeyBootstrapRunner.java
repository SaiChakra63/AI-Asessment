package com.shortener.security;

import com.shortener.model.ApiClient;
import com.shortener.repository.ApiClientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Provisions one assessment API client at startup. Bootstrap is create-only by
 * default; enabling update-existing rotates credentials and reactivates that
 * client, so the flag is intended for one controlled restart only.
 */
@Component
public class ApiKeyBootstrapRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyBootstrapRunner.class);
    private static final Set<String> ALLOWED_AUTHORITIES = Set.of(
            "URL_WRITE", "ANALYTICS_READ", "OPS_READ", "ADMIN");

    private final ApiClientRepository apiClientRepository;
    private final ApiKeyHasher apiKeyHasher;
    private final String clientId;
    private final String displayName;
    private final String rawApiKey;
    private final String configuredAuthorities;
    private final boolean updateExisting;

    public ApiKeyBootstrapRunner(
            ApiClientRepository apiClientRepository,
            ApiKeyHasher apiKeyHasher,
            @Value("${app.security.bootstrap.client-id:local-assessment-client}") String clientId,
            @Value("${app.security.bootstrap.display-name:Local assessment client}") String displayName,
            @Value("${app.security.bootstrap.api-key:}") String rawApiKey,
            @Value("${app.security.bootstrap.authorities:URL_WRITE,ANALYTICS_READ,OPS_READ}")
            String configuredAuthorities,
            @Value("${app.security.bootstrap.update-existing:false}") boolean updateExisting
    ) {
        this.apiClientRepository = apiClientRepository;
        this.apiKeyHasher = apiKeyHasher;
        this.clientId = clientId;
        this.displayName = displayName;
        this.rawApiKey = rawApiKey;
        this.configuredAuthorities = configuredAuthorities;
        this.updateExisting = updateExisting;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String normalizedApiKey = rawApiKey.trim();
        if (normalizedApiKey.isBlank()) {
            LOGGER.info("No bootstrap API key is configured; no API client will be created or updated. "
                    + "Existing active clients can authenticate when their original API key pepper is configured.");
            return;
        }
        validateConfiguration(normalizedApiKey);
        var existingClient = apiClientRepository.findById(clientId);
        if (existingClient.isPresent() && !updateExisting) {
            LOGGER.info("API client '{}' already exists; bootstrap is create-only and left it unchanged", clientId);
            return;
        }
        String authorityValue = parseAuthorities().stream().collect(Collectors.joining(","));
        ApiClient client = existingClient
                .orElseGet(() -> ApiClient.builder().clientId(clientId).build());
        client.setDisplayName(displayName.trim());
        client.setApiKeyDigest(apiKeyHasher.digest(normalizedApiKey));
        client.setAuthorities(authorityValue);
        client.setActive(true);
        apiClientRepository.save(client);
        LOGGER.info("{} API client '{}' with authorities {}",
                existingClient.isPresent() ? "Updated" : "Provisioned",
                clientId,
                authorityValue);
    }

    private void validateConfiguration(String normalizedApiKey) {
        if (!apiKeyHasher.isConfigured()) {
            throw new IllegalStateException(
                    "APP_BOOTSTRAP_API_KEY was provided but API_KEY_PEPPER is missing or unsafe");
        }
        if (normalizedApiKey.startsWith("replace-with-")) {
            throw new IllegalStateException(
                    "Bootstrap API key must not use the value from .env.example");
        }
        if (normalizedApiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("Bootstrap API key must contain at least 32 bytes");
        }
        if (!clientId.matches("[a-zA-Z0-9._-]{3,100}") || "legacy-system".equals(clientId)) {
            throw new IllegalStateException("Bootstrap API client ID is invalid or reserved");
        }
        if (displayName.isBlank() || displayName.length() > 150) {
            throw new IllegalStateException("Bootstrap API client display name is invalid");
        }
    }

    private Set<String> parseAuthorities() {
        Set<String> authorities = Arrays.stream(configuredAuthorities.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (authorities.isEmpty() || !ALLOWED_AUTHORITIES.containsAll(authorities)) {
            throw new IllegalStateException(
                    "Bootstrap authorities must be selected from " + ALLOWED_AUTHORITIES);
        }
        return authorities;
    }
}
