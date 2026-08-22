package com.shortener.config;

import com.shortener.security.ApiKeyAuthenticationFilter;
import com.shortener.security.RestSecurityErrorWriter;
import com.shortener.service.RateLimitService;
import com.shortener.service.RequestMetadataExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    RateLimitFilter rateLimitFilter(
            RateLimitService rateLimitService,
            RequestMetadataExtractor metadataExtractor,
            ObjectMapper objectMapper
    ) {
        return new RateLimitFilter(rateLimitService, metadataExtractor, objectMapper);
    }

    /**
     * The API-key filter belongs only to Spring Security's filter chain. Without this
     * registration override, Spring Boot can also install the component as a servlet
     * filter and cause a second authentication/database lookup for one request.
     */
    @Bean
    FilterRegistrationBean<ApiKeyAuthenticationFilter> disableServletFilterRegistration(
            ApiKeyAuthenticationFilter apiKeyAuthenticationFilter
    ) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration =
                new FilterRegistrationBean<>(apiKeyAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Like the API-key filter, the rate limiter belongs only to Spring
     * Security's ordered chain and must not also run as a servlet filter.
     */
    @Bean
    FilterRegistrationBean<RateLimitFilter> disableRateLimitServletFilterRegistration(
            RateLimitFilter rateLimitFilter
    ) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(rateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RateLimitFilter rateLimitFilter,
            ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
            RestSecurityErrorWriter errorWriter
    ) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/v1/urls/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/urls/shorten")
                        .hasAnyRole("URL_WRITE", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/urls/*")
                        .hasAnyRole("URL_WRITE", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/analytics/**")
                        .hasAnyRole("ANALYTICS_READ", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/health")
                        .hasAnyRole("OPS_READ", "ADMIN")
                        .requestMatchers(
                                "/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus",
                                "/api-docs", "/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errorWriter.write(
                                        request,
                                        response,
                                        HttpServletResponse.SC_UNAUTHORIZED,
                                        "AUTHENTICATION_REQUIRED",
                                        "A valid API key is required"))
                        .accessDeniedHandler((request, response, exception) ->
                                errorWriter.write(
                                        request,
                                        response,
                                        HttpServletResponse.SC_FORBIDDEN,
                                        "ACCESS_DENIED",
                                        "The API key does not grant this operation")))
                .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, ApiKeyAuthenticationFilter.class)
                .build();
    }
}
