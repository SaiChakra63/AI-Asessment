package com.shortener.security;

import com.shortener.model.ApiClient;
import com.shortener.repository.ApiClientRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final ApiClientRepository apiClientRepository;
    private final ApiKeyHasher apiKeyHasher;
    private final RestSecurityErrorWriter errorWriter;
    private final String headerName;

    public ApiKeyAuthenticationFilter(
            ApiClientRepository apiClientRepository,
            ApiKeyHasher apiKeyHasher,
            RestSecurityErrorWriter errorWriter,
            @Value("${app.security.api-key.header:X-API-Key}") String headerName
    ) {
        this.apiClientRepository = apiClientRepository;
        this.apiKeyHasher = apiKeyHasher;
        this.errorWriter = errorWriter;
        this.headerName = headerName;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = applicationPath(request);
        if ("GET".equalsIgnoreCase(request.getMethod())
                && path.startsWith("/api/v1/urls/")) {
            String shortCode = path.substring("/api/v1/urls/".length());
            if (!shortCode.isBlank() && !shortCode.contains("/")) {
                return true;
            }
        }
        return path.equals("/actuator/health")
                || path.startsWith("/actuator/health/")
                || path.equals("/actuator/prometheus")
                || path.equals("/api-docs")
                || path.startsWith("/api-docs/")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/swagger-ui/");
    }

    private String applicationPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank()
                && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String rawApiKey = request.getHeader(headerName);
        if (rawApiKey == null || rawApiKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!apiKeyHasher.isConfigured() || rawApiKey.length() > 512) {
            reject(request, response);
            return;
        }

        Optional<ApiClient> client;
        try {
            client = apiClientRepository.findByApiKeyDigestAndActiveTrue(
                    apiKeyHasher.digest(rawApiKey.trim()));
        } catch (DataAccessException exception) {
            authenticationStoreUnavailable(request, response, exception);
            return;
        }
        if (client.isEmpty()) {
            reject(request, response);
            return;
        }

        Set<String> roles = Arrays.stream(client.get().getAuthorities().split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> "ROLE_" + value)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        AuthenticatedClient principal = new AuthenticatedClient(client.get().getClientId(), roles);
        var authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                roles.stream().map(SimpleGrantedAuthority::new).toList()
        );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private void authenticationStoreUnavailable(
            HttpServletRequest request,
            HttpServletResponse response,
            DataAccessException exception
    ) throws IOException {
        SecurityContextHolder.clearContext();
        LOGGER.error("API-client authentication store is unavailable", exception);
        errorWriter.write(
                request,
                response,
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "AUTHENTICATION_SERVICE_UNAVAILABLE",
                "Authentication service is temporarily unavailable"
        );
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        errorWriter.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED.value(),
                "INVALID_API_KEY",
                "A valid API key is required"
        );
    }
}
