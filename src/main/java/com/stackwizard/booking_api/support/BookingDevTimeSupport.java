package com.stackwizard.booking_api.support;

import com.stackwizard.booking_api.config.BookingDevProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Arrays;

@Component
public class BookingDevTimeSupport {

    private final Environment environment;
    private final BookingDevProperties devProperties;

    public BookingDevTimeSupport(Environment environment, BookingDevProperties devProperties) {
        this.environment = environment;
        this.devProperties = devProperties;
    }

    /**
     * "Today" for booking UI defaults: fixed {@link BookingDevProperties#getReferenceDate()} in dev/local when set,
     * otherwise the real calendar date.
     */
    public LocalDate todayForBookingUi() {
        return resolvePeriodsFromDate(null);
    }

    /**
     * Anchor date for resource map periods. When {@link BookingDevProperties#getReferenceDate()} is set and the app
     * runs with profile {@code dev} or {@code local}, that date is used <strong>even if the client passes
     * {@code fromDate}</strong> (e.g. CMS always sending real "today") so you can test past seasons locally.
     */
    public LocalDate resolvePeriodsFromDate(LocalDate requestFromDate) {
        LocalDate ref = devProperties.getReferenceDate();
        if (ref != null && matchesDevLikeProfile()) {
            return ref;
        }
        return requestFromDate != null ? requestFromDate : LocalDate.now();
    }

    private boolean matchesDevLikeProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "dev".equalsIgnoreCase(p) || "local".equalsIgnoreCase(p));
    }
}
