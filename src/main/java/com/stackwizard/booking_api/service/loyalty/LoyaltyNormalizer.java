package com.stackwizard.booking_api.service.loyalty;

import java.util.Locale;

/** Canonical forms used for matching PMS reservations to members. */
public final class LoyaltyNormalizer {
    private LoyaltyNormalizer() {
    }

    public static String email(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() || !s.contains("@") ? null : s;
    }

    /** Digits only, leading 00 turned into +; good enough for equality matching across exports. */
    public static String phone(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        boolean plus = s.startsWith("+");
        String digits = s.replaceAll("\\D", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
            plus = true;
        }
        if (digits.length() < 6) {
            return null;
        }
        return plus ? "+" + digits : digits;
    }

    public static String code(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    public static String text(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        return s.isEmpty() ? null : s;
    }
}
