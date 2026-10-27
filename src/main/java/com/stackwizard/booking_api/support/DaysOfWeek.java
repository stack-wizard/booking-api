package com.stackwizard.booking_api.support;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Weekday bitmask of {@code price_list.days_of_week}: Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64; null = every
 * day. JSON uses a list of {@code MON..SUN}.
 */
public final class DaysOfWeek {
    private static final String[] CODES = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};

    private DaysOfWeek() {
    }

    /** Null or empty list = every day (null mask). A list with all seven days is also stored as null. */
    public static Short toMask(List<String> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        int mask = 0;
        for (String day : days) {
            mask |= bit(parse(day));
        }
        return mask == 127 ? null : (short) mask;
    }

    public static List<String> toList(Short mask) {
        if (mask == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            if ((mask & (1 << i)) != 0) {
                out.add(CODES[i]);
            }
        }
        return out;
    }

    /** "FRI,SAT", or null for every day. */
    public static String label(Short mask) {
        List<String> days = toList(mask);
        return days == null ? null : String.join(",", days);
    }

    public static boolean matches(Short mask, LocalDate date) {
        return mask == null || date == null || (mask & bit(date.getDayOfWeek())) != 0;
    }

    public static int bit(DayOfWeek day) {
        return 1 << (day.getValue() - 1);
    }

    private static DayOfWeek parse(String day) {
        if (day == null || day.isBlank()) {
            throw new IllegalArgumentException("daysOfWeek must contain MON, TUE, WED, THU, FRI, SAT or SUN");
        }
        String code = day.trim().toUpperCase(Locale.ROOT);
        for (int i = 0; i < 7; i++) {
            if (CODES[i].equals(code) || DayOfWeek.of(i + 1).name().equals(code)) {
                return DayOfWeek.of(i + 1);
            }
        }
        throw new IllegalArgumentException("Unknown day '" + day + "' in daysOfWeek; use MON, TUE, WED, THU, FRI, SAT or SUN");
    }
}
