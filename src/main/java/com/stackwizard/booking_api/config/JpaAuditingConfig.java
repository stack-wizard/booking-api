package com.stackwizard.booking_api.config;

import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.security.AuthUserAccessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.OffsetDateTime;
import java.util.Optional;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {
    @Bean
    public AuditorAware<Long> auditorAware(AuthUserAccessor authUserAccessor) {
        return () -> authUserAccessor.currentAppUser().map(AppUser::getId);
    }

    @Bean
    public DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(OffsetDateTime.now());
    }
}
