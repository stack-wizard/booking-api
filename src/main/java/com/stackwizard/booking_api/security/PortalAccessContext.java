package com.stackwizard.booking_api.security;

import java.util.Set;

/**
 * What a portal caller may see: one event (anonymous link) or the events of their contact/account
 * (Platform user linked through crm_contact.platform_user_id).
 */
public record PortalAccessContext(Long tenantId, Set<Long> eventIds, Long contactId, Mode mode, String tokenValue) {

    public enum Mode {
        TOKEN, PLATFORM_USER
    }

    public boolean allows(Long eventId) {
        return eventId != null && eventIds.contains(eventId);
    }
}
