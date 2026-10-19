package com.stackwizard.booking_api.security;

import java.util.Set;

/**
 * What a portal caller may see: one booking (anonymous link) or the bookings of their contact/account
 * (Platform user linked through crm_contact.platform_user_id).
 */
public record PortalAccessContext(Long tenantId, Set<Long> bookingIds, Long contactId, Mode mode, String tokenValue) {

    public enum Mode {
        TOKEN, PLATFORM_USER
    }

    public boolean allows(Long bookingId) {
        return bookingId != null && bookingIds.contains(bookingId);
    }
}
