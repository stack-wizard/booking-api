package com.stackwizard.booking_api.service.loyalty.imports;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Typed access to one staged CSV row through the batch's column mapping (canonical field → file header).
 * Parse problems are collected, not thrown, so one pass reports every error of the row.
 */
public final class ImportRow {
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d.M.uuuu"),
            DateTimeFormatter.ofPattern("d.M.uuuu."),
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            caseInsensitive("d-MMM-uu"),
            caseInsensitive("d-MMM-uuuu"),
            DateTimeFormatter.ofPattern("uuuuMMdd"));

    /** OPERA prints months as OCT; java.time only accepts Oct unless told to ignore case. */
    private static DateTimeFormatter caseInsensitive(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH);
    }

    private final JsonNode raw;
    private final Map<String, String> mapping;
    private final DateTimeFormatter preferredDate;
    private final List<String> errors = new ArrayList<>();

    public ImportRow(JsonNode raw, Map<String, String> mapping, String dateFormat) {
        this.raw = raw;
        this.mapping = mapping;
        this.preferredDate = dateFormat == null || dateFormat.isBlank() ? null : DateTimeFormatter.ofPattern(dateFormat);
    }

    public JsonNode raw() {
        return raw;
    }

    public List<String> errors() {
        return errors;
    }

    public void error(String message) {
        errors.add(message);
    }

    public boolean has(String field) {
        return text(field) != null;
    }

    public String text(String field) {
        String header = mapping.get(field);
        if (header == null) {
            return null;
        }
        JsonNode v = raw.get(header);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    public String required(String field) {
        String v = text(field);
        if (v == null) {
            errors.add(field + " is required");
        }
        return v;
    }

    public Integer integer(String field) {
        String v = text(field);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v.replace(',', '.')).intValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            errors.add(field + " is not a whole number: " + v);
            return null;
        }
    }

    public BigDecimal decimal(String field) {
        String v = text(field);
        if (v == null) {
            return null;
        }
        try {
            String normalized = v.contains(",") && !v.contains(".") ? v.replace(',', '.') : v.replace(",", "");
            return new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            errors.add(field + " is not a number: " + v);
            return null;
        }
    }

    public LocalDate date(String field) {
        String v = text(field);
        if (v == null) {
            return null;
        }
        LocalDate d = parseDate(v);
        if (d == null) {
            errors.add(field + " is not a date: " + v);
        }
        return d;
    }

    /** yes/no, true/false, 1/0, da/ne, Y/N; a date also counts as yes (e.g. "wallet added" exported as a date). */
    public Boolean bool(String field) {
        String v = text(field);
        if (v == null) {
            return null;
        }
        switch (v.toLowerCase(Locale.ROOT)) {
            case "yes", "y", "true", "1", "da", "d", "x" -> {
                return true;
            }
            case "no", "n", "false", "0", "ne" -> {
                return false;
            }
            default -> {
                if (parseDate(v) != null) {
                    return true;
                }
                errors.add(field + " is not yes/no: " + v);
                return null;
            }
        }
    }

    LocalDate parseDate(String v) {
        String s = v.trim();
        if (preferredDate != null) {
            try {
                return LocalDate.parse(s, preferredDate);
            } catch (DateTimeParseException ignored) {
                // fall through to the common formats
            }
        }
        String dateOnly = s.length() > 10 && (s.charAt(10) == 'T' || s.charAt(10) == ' ') ? s.substring(0, 10) : s;
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(dateOnly, f);
            } catch (DateTimeParseException ignored) {
                // next
            }
        }
        try {
            return OffsetDateTime.parse(s).toLocalDate();
        } catch (DateTimeParseException ignored) {
            // next
        }
        try {
            return LocalDateTime.parse(s).toLocalDate();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
