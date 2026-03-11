package com.stackwizard.booking_api.repository.specification;

import com.stackwizard.booking_api.dto.PaymentTransactionSearchCriteria;
import com.stackwizard.booking_api.model.PaymentTransaction;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PaymentTransactionSpecifications {
    private PaymentTransactionSpecifications() {
    }

    public static Specification<PaymentTransaction> byCriteria(PaymentTransactionSearchCriteria criteria) {
        return (root, query, cb) -> {
            if (criteria == null) {
                return cb.conjunction();
            }

            List<Predicate> predicates = new ArrayList<>();

            if (criteria.getTenantId() != null) {
                predicates.add(cb.equal(root.get("tenantId"), criteria.getTenantId()));
            }
            if (criteria.getTransactionId() != null) {
                predicates.add(cb.equal(root.get("id"), criteria.getTransactionId()));
            }
            if (criteria.getReservationRequestId() != null) {
                predicates.add(cb.equal(root.get("reservationRequestId"), criteria.getReservationRequestId()));
            }
            if (criteria.getPaymentIntentId() != null) {
                predicates.add(cb.equal(root.get("paymentIntentId"), criteria.getPaymentIntentId()));
            }
            if (hasValues(criteria.getPaymentTypes())) {
                predicates.add(inUpperCase(cb, root.get("paymentType").as(String.class), criteria.getPaymentTypes()));
            }
            if (hasValues(criteria.getStatuses())) {
                predicates.add(inUpperCase(cb, root.get("status").as(String.class), criteria.getStatuses()));
            }
            if (hasValues(criteria.getCurrencies())) {
                predicates.add(inUpperCase(cb, root.get("currency").as(String.class), criteria.getCurrencies()));
            }
            if (StringUtils.hasText(criteria.getExternalRef())) {
                predicates.add(containsIgnoreCase(cb, root.get("externalRef").as(String.class), criteria.getExternalRef()));
            }
            if (StringUtils.hasText(criteria.getNote())) {
                predicates.add(containsIgnoreCase(cb, root.get("note").as(String.class), criteria.getNote()));
            }
            if (criteria.getCreatedFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), criteria.getCreatedFrom()));
            }
            if (criteria.getCreatedTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), criteria.getCreatedTo()));
            }
            if (criteria.getAmountMin() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), criteria.getAmountMin()));
            }
            if (criteria.getAmountMax() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("amount"), criteria.getAmountMax()));
            }

            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static Predicate inUpperCase(CriteriaBuilder cb, Expression<String> field, List<String> values) {
        CriteriaBuilder.In<String> in = cb.in(cb.upper(field));
        values.forEach(in::value);
        return in;
    }

    private static Predicate containsIgnoreCase(CriteriaBuilder cb, Expression<String> field, String value) {
        String normalized = value == null ? "" : value.trim();
        String likePattern = "%" + escapeLike(normalized.toLowerCase(Locale.ROOT)) + "%";
        return cb.like(cb.lower(field), likePattern, '\\');
    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static boolean hasValues(List<String> values) {
        return values != null && !values.isEmpty();
    }
}
