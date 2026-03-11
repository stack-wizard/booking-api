package com.stackwizard.booking_api.repository.specification;

import com.stackwizard.booking_api.dto.ReservationRequestSearchCriteria;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ReservationRequestSpecifications {
    private ReservationRequestSpecifications() {
    }

    public static Specification<ReservationRequest> byCriteria(ReservationRequestSearchCriteria criteria) {
        return (root, query, cb) -> {
            if (criteria == null) {
                return cb.conjunction();
            }

            List<Predicate> predicates = new ArrayList<>();

            if (criteria.getTenantId() != null) {
                predicates.add(cb.equal(root.get("tenantId"), criteria.getTenantId()));
            }
            if (criteria.getRequestId() != null) {
                predicates.add(cb.equal(root.get("id"), criteria.getRequestId()));
            }
            if (StringUtils.hasText(criteria.getConfirmationNumber())) {
                predicates.add(containsIgnoreCase(cb, root.get("confirmationCode").as(String.class), criteria.getConfirmationNumber()));
            }
            if (hasValues(criteria.getStatuses())) {
                predicates.add(inUpperCase(cb, root.get("status").as(String.class), criteria.getStatuses()));
            }
            if (hasValues(criteria.getTypes())) {
                predicates.add(inUpperCase(cb, root.get("type").as(String.class), criteria.getTypes()));
            }

            if (criteria.getCreatedFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), criteria.getCreatedFrom()));
            }
            if (criteria.getCreatedTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), criteria.getCreatedTo()));
            }
            if (criteria.getExpiresFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("expiresAt"), criteria.getExpiresFrom()));
            }
            if (criteria.getExpiresTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("expiresAt"), criteria.getExpiresTo()));
            }
            if (criteria.getConfirmedFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("confirmedAt"), criteria.getConfirmedFrom()));
            }
            if (criteria.getConfirmedTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("confirmedAt"), criteria.getConfirmedTo()));
            }

            if (StringUtils.hasText(criteria.getCustomer())) {
                predicates.add(buildCustomerAnyPredicate(root, query, cb, criteria.getCustomer()));
            }
            if (StringUtils.hasText(criteria.getCustomerName())) {
                predicates.add(buildCustomerFieldPredicate(root, query, cb, "customerName", criteria.getCustomerName()));
            }
            if (StringUtils.hasText(criteria.getCustomerEmail())) {
                predicates.add(buildCustomerFieldPredicate(root, query, cb, "customerEmail", criteria.getCustomerEmail()));
            }
            if (StringUtils.hasText(criteria.getCustomerPhone())) {
                predicates.add(buildCustomerFieldPredicate(root, query, cb, "customerPhone", criteria.getCustomerPhone()));
            }

            Predicate reservationPredicate = buildReservationPredicate(root, query, cb, criteria);
            if (reservationPredicate != null) {
                predicates.add(reservationPredicate);
            }

            if (hasValues(criteria.getPaymentIntentStatuses())) {
                predicates.add(buildPaymentIntentPredicate(root, query, cb, criteria.getPaymentIntentStatuses()));
            }

            if (predicates.isEmpty()) {
                return cb.conjunction();
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static Predicate buildCustomerAnyPredicate(Root<ReservationRequest> root,
                                                       CriteriaQuery<?> query,
                                                       CriteriaBuilder cb,
                                                       String customer) {
        Predicate requestMatch = cb.or(
                containsIgnoreCase(cb, root.get("customerName").as(String.class), customer),
                containsIgnoreCase(cb, root.get("customerEmail").as(String.class), customer),
                containsIgnoreCase(cb, root.get("customerPhone").as(String.class), customer)
        );
        Predicate reservationMatch = buildReservationCustomerAnyPredicate(root, query, cb, customer);
        return cb.or(requestMatch, reservationMatch);
    }

    private static Predicate buildCustomerFieldPredicate(Root<ReservationRequest> root,
                                                         CriteriaQuery<?> query,
                                                         CriteriaBuilder cb,
                                                         String requestField,
                                                         String value) {
        Predicate requestMatch = containsIgnoreCase(cb, root.get(requestField).as(String.class), value);
        Predicate reservationMatch = buildReservationFieldPredicate(root, query, cb, requestField, value);
        return cb.or(requestMatch, reservationMatch);
    }

    private static Predicate buildReservationCustomerAnyPredicate(Root<ReservationRequest> root,
                                                                  CriteriaQuery<?> query,
                                                                  CriteriaBuilder cb,
                                                                  String value) {
        Subquery<Long> sq = query.subquery(Long.class);
        Root<Reservation> reservation = sq.from(Reservation.class);
        Predicate link = cb.equal(reservation.get("request").get("id"), root.get("id"));
        Predicate customerMatch = cb.or(
                containsIgnoreCase(cb, reservation.get("customerName").as(String.class), value),
                containsIgnoreCase(cb, reservation.get("customerEmail").as(String.class), value),
                containsIgnoreCase(cb, reservation.get("customerPhone").as(String.class), value)
        );
        sq.select(cb.literal(1L)).where(link, customerMatch);
        return cb.exists(sq);
    }

    private static Predicate buildReservationFieldPredicate(Root<ReservationRequest> root,
                                                            CriteriaQuery<?> query,
                                                            CriteriaBuilder cb,
                                                            String field,
                                                            String value) {
        Subquery<Long> sq = query.subquery(Long.class);
        Root<Reservation> reservation = sq.from(Reservation.class);
        Predicate link = cb.equal(reservation.get("request").get("id"), root.get("id"));
        Predicate fieldMatch = containsIgnoreCase(cb, reservation.get(field).as(String.class), value);
        sq.select(cb.literal(1L)).where(link, fieldMatch);
        return cb.exists(sq);
    }

    private static Predicate buildReservationPredicate(Root<ReservationRequest> root,
                                                       CriteriaQuery<?> query,
                                                       CriteriaBuilder cb,
                                                       ReservationRequestSearchCriteria criteria) {
        boolean hasReservationFilters =
                criteria.getReservationId() != null
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
        if (!hasReservationFilters) {
            return null;
        }

        Subquery<Long> sq = query.subquery(Long.class);
        Root<Reservation> reservation = sq.from(Reservation.class);
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(reservation.get("request").get("id"), root.get("id")));

        if (criteria.getReservationId() != null) {
            predicates.add(cb.equal(reservation.get("id"), criteria.getReservationId()));
        }
        if (hasValues(criteria.getReservationStatuses())) {
            predicates.add(inUpperCase(cb, reservation.get("status").as(String.class), criteria.getReservationStatuses()));
        }
        if (criteria.getProductId() != null) {
            predicates.add(cb.equal(reservation.get("productId"), criteria.getProductId()));
        }
        if (StringUtils.hasText(criteria.getProductName())) {
            Root<Product> product = sq.from(Product.class);
            predicates.add(cb.equal(product.get("id"), reservation.get("productId")));
            if (criteria.getTenantId() != null) {
                predicates.add(cb.equal(product.get("tenantId"), criteria.getTenantId()));
            }
            predicates.add(containsIgnoreCase(cb, product.get("name").as(String.class), criteria.getProductName()));
        }
        if (criteria.getResourceId() != null) {
            predicates.add(cb.equal(reservation.get("requestedResource").get("id"), criteria.getResourceId()));
        }
        if (StringUtils.hasText(criteria.getResourceName())) {
            Join<Reservation, ?> resource = reservation.join("requestedResource", JoinType.INNER);
            predicates.add(containsIgnoreCase(cb, resource.get("name").as(String.class), criteria.getResourceName()));
        }
        if (criteria.getReservationFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(reservation.get("endsAt"), criteria.getReservationFrom()));
        }
        if (criteria.getReservationTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(reservation.get("startsAt"), criteria.getReservationTo()));
        }
        if (criteria.getReservationStartsFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(reservation.get("startsAt"), criteria.getReservationStartsFrom()));
        }
        if (criteria.getReservationStartsTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(reservation.get("startsAt"), criteria.getReservationStartsTo()));
        }
        if (criteria.getReservationEndsFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(reservation.get("endsAt"), criteria.getReservationEndsFrom()));
        }
        if (criteria.getReservationEndsTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(reservation.get("endsAt"), criteria.getReservationEndsTo()));
        }

        sq.select(cb.literal(1L)).where(predicates.toArray(new Predicate[0]));
        return cb.exists(sq);
    }

    private static Predicate buildPaymentIntentPredicate(Root<ReservationRequest> root,
                                                         CriteriaQuery<?> query,
                                                         CriteriaBuilder cb,
                                                         List<String> statuses) {
        Subquery<Long> sq = query.subquery(Long.class);
        Root<PaymentIntent> intent = sq.from(PaymentIntent.class);
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(intent.get("reservationRequestId"), root.get("id")));
        predicates.add(inUpperCase(cb, intent.get("status").as(String.class), statuses));
        sq.select(cb.literal(1L)).where(predicates.toArray(new Predicate[0]));
        return cb.exists(sq);
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
