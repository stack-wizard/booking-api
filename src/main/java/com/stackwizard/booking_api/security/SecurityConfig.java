package com.stackwizard.booking_api.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {
    private static final String MONRI_WEBHOOK_PATH = "/api/payments/providers/monri/webhook/**";
    private static final String MONRI_WEBHOOK_PATH_PREFIXED = "/booking-api/api/payments/providers/monri/webhook/**";
    private static final String MONRI_CALLBACK_PATH = "/api/payments/providers/monri/callback/**";
    private static final String MONRI_CALLBACK_PATH_PREFIXED = "/booking-api/api/payments/providers/monri/callback/**";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   PlatformAuthFilter platformAuthFilter,
                                                   PlatformJwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, MONRI_WEBHOOK_PATH).permitAll()
                .requestMatchers(HttpMethod.OPTIONS, MONRI_WEBHOOK_PATH).permitAll()
                .requestMatchers(HttpMethod.POST, MONRI_WEBHOOK_PATH_PREFIXED).permitAll()
                .requestMatchers(HttpMethod.OPTIONS, MONRI_WEBHOOK_PATH_PREFIXED).permitAll()
                .requestMatchers(HttpMethod.POST, MONRI_CALLBACK_PATH).permitAll()
                .requestMatchers(HttpMethod.OPTIONS, MONRI_CALLBACK_PATH).permitAll()
                .requestMatchers(HttpMethod.POST, MONRI_CALLBACK_PATH_PREFIXED).permitAll()
                .requestMatchers(HttpMethod.OPTIONS, MONRI_CALLBACK_PATH_PREFIXED).permitAll()
                .requestMatchers(
                    "/api/public/reservation-requests/**",
                    "/booking-api/api/public/reservation-requests/**",
                    "/actuator/health",
                    "/booking-api/actuator/health",
                    "/actuator/info",
                    "/booking-api/actuator/info",
                    "/v3/api-docs/**",
                    "/booking-api/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/booking-api/swagger-ui/**",
                    "/swagger-ui.html",
                    "/booking-api/swagger-ui.html"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            )
            .addFilterAfter(platformAuthFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * PlatformAuthFilter depends on JPA; disable servlet-container registration so Tomcat
     * does not instantiate it before EntityManagerFactory. It runs only via SecurityFilterChain.
     */
    @Bean
    public FilterRegistrationBean<PlatformAuthFilter> platformAuthFilterRegistration(
            PlatformAuthFilter platformAuthFilter) {
        FilterRegistrationBean<PlatformAuthFilter> registration =
                new FilterRegistrationBean<>(platformAuthFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "X-Tenant-Id"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
