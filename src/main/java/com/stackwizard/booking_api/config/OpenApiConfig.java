package com.stackwizard.booking_api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger "Authorize": a Platform access token (Bearer) and the Platform tenant UUID (X-Tenant-Id) are sent with
 * every call made from the UI, the same two headers booking-admin sends.
 */
@Configuration
public class OpenApiConfig {
    static final String BEARER = "platformToken";
    static final String TENANT = "tenantId";

    @Bean
    public OpenAPI bookingApiOpenApi() {
        return new OpenAPI()
                .info(new Info().title("booking-api"))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Mikos Platform access token (user or m2m)"))
                        .addSecuritySchemes(TENANT, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Tenant-Id")
                                .description("Platform tenant UUID (organization or hotel)")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER).addList(TENANT));
    }
}
