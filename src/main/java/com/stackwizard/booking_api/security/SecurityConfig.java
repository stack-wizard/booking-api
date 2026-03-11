package com.stackwizard.booking_api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;
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

    private final ApiTokenAuthenticationFilter apiTokenFilter;
    private final JwtAuthenticationFilter jwtFilter;
    private final UserDetailsServiceImpl userDetailsService;

    public SecurityConfig(ApiTokenAuthenticationFilter apiTokenFilter,
                          JwtAuthenticationFilter jwtFilter,
                          UserDetailsServiceImpl userDetailsService) {
        this.apiTokenFilter = apiTokenFilter;
        this.jwtFilter = jwtFilter;
        this.userDetailsService = userDetailsService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
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
                    "/api/auth/**",
                    "/booking-api/api/auth/**",
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
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(apiTokenFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
