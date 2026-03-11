package com.stackwizard.booking_api.repository.specification;

import com.stackwizard.booking_api.dto.InvoiceSearchCriteria;
import com.stackwizard.booking_api.model.Invoice;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class InvoiceSpecifications {
    private InvoiceSpecifications() {
    }

    public static Specification<Invoice> byCriteria(InvoiceSearchCriteria criteria) {
        return (root, query, cb) -> {
            if (criteria == null) {
                return cb.conjunction();
            }

            List<Predicate> predicates = new ArrayList<>();

            if (criteria.getTenantId() != null) {
                predicates.add(cb.equal(root.get("tenantId"), criteria.getTenantId()));
            }
            if (criteria.getInvoiceId() != null) {
                predicates.add(cb.equal(root.get("id"), criteria.getInvoiceId()));
            }
            if (criteria.getReservationRequestId() != null) {
                predicates.add(cb.equal(root.get("reservationRequestId"), criteria.getReservationRequestId()));
            }
            if (StringUtils.hasText(criteria.getInvoiceNumber())) {
                predicates.add(containsIgnoreCase(cb, root.get("invoiceNumber").as(String.class), criteria.getInvoiceNumber()));
            }
            if (hasValues(criteria.getInvoiceTypes())) {
                predicates.add(inUpperCase(cb, root.get("invoiceType").as(String.class), criteria.getInvoiceTypes()));
            }
            if (hasValues(criteria.getStatuses())) {
                predicates.add(inUpperCase(cb, root.get("status").as(String.class), criteria.getStatuses()));
            }
            if (hasValues(criteria.getPaymentStatuses())) {
                predicates.add(inUpperCase(cb, root.get("paymentStatus").as(String.class), criteria.getPaymentStatuses()));
            }
            if (hasValues(criteria.getFiscalizationStatuses())) {
                predicates.add(inUpperCase(cb, root.get("fiscalizationStatus").as(String.class), criteria.getFiscalizationStatuses()));
            }
            if (hasValues(criteria.getCurrencies())) {
                predicates.add(inUpperCase(cb, root.get("currency").as(String.class), criteria.getCurrencies()));
            }
            if (StringUtils.hasText(criteria.getReferenceTable())) {
                predicates.add(cb.equal(cb.lower(root.get("referenceTable").as(String.class)),
                        criteria.getReferenceTable().trim().toLowerCase(Locale.ROOT)));
            }
            if (criteria.getReferenceId() != null) {
                predicates.add(cb.equal(root.get("referenceId"), criteria.getReferenceId()));
            }

            if (StringUtils.hasText(criteria.getCustomer())) {
                predicates.add(cb.or(
                        containsIgnoreCase(cb, root.get("customerName").as(String.class), criteria.getCustomer()),
                        containsIgnoreCase(cb, root.get("customerEmail").as(String.class), criteria.getCustomer()),
                        containsIgnoreCase(cb, root.get("customerPhone").as(String.class), criteria.getCustomer())
                ));
            }
            if (StringUtils.hasText(criteria.getCustomerName())) {
                predicates.add(containsIgnoreCase(cb, root.get("customerName").as(String.class), criteria.getCustomerName()));
            }
            if (StringUtils.hasText(criteria.getCustomerEmail())) {
                predicates.add(containsIgnoreCase(cb, root.get("customerEmail").as(String.class), criteria.getCustomerEmail()));
            }
            if (StringUtils.hasText(criteria.getCustomerPhone())) {
                predicates.add(containsIgnoreCase(cb, root.get("customerPhone").as(String.class), criteria.getCustomerPhone()));
            }

            if (criteria.getInvoiceDateFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("invoiceDate"), criteria.getInvoiceDateFrom()));
            }
            if (criteria.getInvoiceDateTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("invoiceDate"), criteria.getInvoiceDateTo()));
            }
            if (criteria.getCreatedFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), criteria.getCreatedFrom()));
            }
            if (criteria.getCreatedTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), criteria.getCreatedTo()));
            }
            if (criteria.getTotalGrossMin() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("totalGross"), criteria.getTotalGrossMin()));
            }
            if (criteria.getTotalGrossMax() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("totalGross"), criteria.getTotalGrossMax()));
            }
            if (criteria.getHasStorno() != null) {
                predicates.add(Boolean.TRUE.equals(criteria.getHasStorno())
                        ? cb.isNotNull(root.get("stornoId"))
                        : cb.isNull(root.get("stornoId")));
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
