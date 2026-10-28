package com.stackwizard.booking_api.service.integration;

/** Subscription filters: exact type, {@code *}, or a prefix glob such as {@code loyalty.*}. */
public final class EventTypeMatcher {
    private EventTypeMatcher() {
    }

    public static boolean matches(String[] patterns, String type) {
        if (patterns == null || patterns.length == 0) {
            return true;
        }
        for (String raw : patterns) {
            if (raw == null) {
                continue;
            }
            String p = raw.trim();
            if (p.equals("*") || p.equals(type)) {
                return true;
            }
            if (p.endsWith(".*") && type.startsWith(p.substring(0, p.length() - 1))) {
                return true;
            }
        }
        return false;
    }
}
