package com.stackwizard.booking_api.repository.specification;

import com.stackwizard.booking_api.dto.ReservationRequestSearchCriteria;
import com.stackwizard.booking_api.dto.ReservationSummaryDto;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.Resource;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Line-level reservation filters aligned with {@link ReservationRequestSpecifications#buildReservationPredicate}.
 * Used by export to emit only reservation lines matching the search criteria, not every line on a matched request.
 */
public final class ReservationLineCriteriaMatcher {

    private ReservationLineCriteriaMatcher() {
    }

    public static boolean hasReservationLineFilters(ReservationRequestSearchCriteria criteria) {
        if (criteria == null) {
            return false;
        }
        return criteria.getReservationId() != null
                || hasValues(criteria.getReservationStatuses())
                || criteria.getProductId() != null
                || StringUtils.hasText(criteria.getProductName())
                || criteria.getResourceId() != null
                || StringUtils.hasText(criteria.getResourceName())
                || criteria.getReservationFrom() != null
                || criteria.getReservationTo() != null
                || criteria.getReservationStartsFrom() != null
                || criteria.getReservationStartsTo() != null
                || criteria.getReservationEndsFrom() != null
                || criteria.getReservationEndsTo() != null;
    }

    public static boolean matches(Reservation reservation, ReservationRequestSearchCriteria criteria) {
        if (reservation == null || criteria == null || !hasReservationLineFilters(criteria)) {
            return true;
        }
        if (criteria.getReservationId() != null && !criteria.getReservationId().equals(reservation.getId())) {
            return false;
        }
        if (!matchesStatus(reservation.getStatus(), criteria)) {
            return false;
        }
        if (criteria.getProductId() != null && !criteria.getProductId().equals(reservation.getProductId())) {
            return false;
        }
        if (StringUtils.hasText(criteria.getProductName())) {
            // Product name is not loaded on Reservation entity; search already scoped the request.
            return true;
        }
        if (criteria.getResourceId() != null) {
            Resource resource = reservation.getRequestedResource();
            if (resource == null || !criteria.getResourceId().equals(resource.getId())) {
                return false;
            }
        }
        if (StringUtils.hasText(criteria.getResourceName())) {
            Resource resource = reservation.getRequestedResource();
            if (resource == null || !containsIgnoreCase(resource.getName(), criteria.getResourceName())) {
                return false;
            }
        }
        return matchesDateFilters(reservation.getStartsAt(), reservation.getEndsAt(), criteria);
    }

    public static boolean matches(ReservationSummaryDto reservation, ReservationRequestSearchCriteria criteria) {
        if (reservation == null || criteria == null || !hasReservationLineFilters(criteria)) {
            return true;
        }
        if (criteria.getReservationId() != null && !criteria.getReservationId().equals(reservation.getId())) {
            return false;
        }
        if (!matchesStatus(reservation.getStatus(), criteria)) {
            return false;
        }
        if (criteria.getProductId() != null && !criteria.getProductId().equals(reservation.getProductId())) {
            return false;
        }
        if (StringUtils.hasText(criteria.getProductName())
                && !containsIgnoreCase(reservation.getProductName(), criteria.getProductName())) {
            return false;
        }
        if (criteria.getResourceId() != null && !criteria.getResourceId().equals(reservation.getRequestedResourceId())) {
            return false;
        }
        if (StringUtils.hasText(criteria.getResourceName())
                && !containsIgnoreCase(reservation.getRequestedResourceName(), criteria.getResourceName())) {
            return false;
        }
        return matchesDateFilters(reservation.getStartsAt(), reservation.getEndsAt(), criteria);
    }

    private static boolean matchesStatus(String status, ReservationRequestSearchCriteria criteria) {
        if (hasValues(criteria.getReservationStatuses())) {
            if (status == null) {
                return false;
            }
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            return criteria.getReservationStatuses().stream()
                    .filter(StringUtils::hasText)
                    .map(v -> v.trim().toUpperCase(Locale.ROOT))
                    .anyMatch(normalized::equals);
        }
        if (hasDateRelatedReservationFilters(criteria)) {
            return status == null || !"CANCELLED".equalsIgnoreCase(status.trim());
        }
        return true;
    }

    private static boolean matchesDateFilters(
            java.time.LocalDateTime startsAt,
            java.time.LocalDateTime endsAt,
            ReservationRequestSearchCriteria criteria) {
        if (criteria.getReservationFrom() != null) {
            if (endsAt == null || endsAt.isBefore(criteria.getReservationFrom())) {
                return false;
            }
        }
        if (criteria.getReservationTo() != null) {
            if (startsAt == null || startsAt.isAfter(criteria.getReservationTo())) {
                return false;
            }
        }
        if (criteria.getReservationStartsFrom() != null) {
            if (startsAt == null || startsAt.isBefore(criteria.getReservationStartsFrom())) {
                return false;
            }
        }
        if (criteria.getReservationStartsTo() != null) {
            if (startsAt == null || startsAt.isAfter(criteria.getReservationStartsTo())) {
                return false;
            }
        }
        if (criteria.getReservationEndsFrom() != null) {
            if (endsAt == null || endsAt.isBefore(criteria.getReservationEndsFrom())) {
                return false;
            }
        }
        if (criteria.getReservationEndsTo() != null) {
            if (endsAt == null || endsAt.isAfter(criteria.getReservationEndsTo())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasDateRelatedReservationFilters(ReservationRequestSearchCriteria criteria) {
        return criteria.getReservationFrom() != null
                || criteria.getReservationTo() != null
                || criteria.getReservationStartsFrom() != null
                || criteria.getReservationStartsTo() != null
                || criteria.getReservationEndsFrom() != null
                || criteria.getReservationEndsTo() != null;
    }

    private static boolean containsIgnoreCase(String field, String value) {
        if (!StringUtils.hasText(field) || !StringUtils.hasText(value)) {
            return false;
        }
        return field.toLowerCase(Locale.ROOT).contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean hasValues(List<String> values) {
        return values != null && !values.isEmpty();
    }
}
