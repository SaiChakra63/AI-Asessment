package com.shortener.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI urlShortenerOpenApi(
            @Value("${app.security.api-key.header:X-API-Key}") String apiKeyHeader
    ) {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(
                        "ApiKeyAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(apiKeyHeader)
                                .description("Scoped API key; the raw value is never stored")))
                .info(new Info()
                        .title("URL Shortener API")
                        .version("3.0.0")
                        .description("URL shortening, public redirects, owner-authorized management, "
                                + "analytics, and operational health."));
    }
}
