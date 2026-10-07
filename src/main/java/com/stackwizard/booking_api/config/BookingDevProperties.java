package com.stackwizard.booking_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

/**
 * Development-only knobs. {@link #referenceDate} is only honored when running with profile {@code dev} or {@code local}
 * (see {@link com.stackwizard.booking_api.support.BookingDevTimeSupport}).
 */
@ConfigurationProperties(prefix = "booking.dev")
public class BookingDevProperties {

    /**
     * When set and profile is dev/local, used instead of {@link LocalDate#now()} for selected defaults
     * (e.g. resource map {@code /periods} when {@code fromDate} is omitted).
     */
    private LocalDate referenceDate;

    /**
     * When true (default), startup seeds one demo sales flow for the Opera hotel {@code DH} tenant
     * if that hotel exists. No-op when DH is missing. Not profile-gated — AWS uses Spring profile {@code prod}.
     * Set {@code false} in SSM on environments that must not get demo data.
     */
    private boolean dhSalesFlowSeed = true;

    public LocalDate getReferenceDate() {
        return referenceDate;
    }

    public void setReferenceDate(LocalDate referenceDate) {
        this.referenceDate = referenceDate;
    }

    public boolean isDhSalesFlowSeed() {
        return dhSalesFlowSeed;
    }

    public void setDhSalesFlowSeed(boolean dhSalesFlowSeed) {
        this.dhSalesFlowSeed = dhSalesFlowSeed;
    }
}
