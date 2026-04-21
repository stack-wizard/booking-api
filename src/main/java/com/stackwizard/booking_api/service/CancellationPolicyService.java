package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.model.CancellationPolicy;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.repository.CancellationPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class CancellationPolicyService {
    private static final DateTimeFormatter SNAPSHOT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CancellationPolicyRepository repo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CancellationPolicyService(CancellationPolicyRepository repo) {
        this.repo = repo;
    }

    public List<CancellationPolicy> findAll() {
        return repo.findAll();
    }

    public List<CancellationPolicy> findByTenantId(Long tenantId) {
        return repo.findByTenantIdOrderByPriorityDescIdDesc(tenantId);
    }

    public Optional<CancellationPolicy> findById(Long id) {
        return repo.findById(id);
    }

    public CancellationPolicy save(CancellationPolicy policy) {
        normalizeAndValidate(policy);
        return repo.save(policy);
    }

    public void deleteById(Long id) {
        repo.deleteById(id);
    }

    public PolicySnapshot resolveBookingSnapshot(Long tenantId, Long productId, LocalDateTime serviceStart) {
        return resolveBookingSnapshotInternal(tenantId, productId, serviceStart, OffsetDateTime.now());
    }

    public PolicySnapshot resolveBookingSnapshotForPeriod(Long tenantId, Long productId, LocalDateTime serviceStart) {
        return resolveBookingSnapshotInternal(tenantId, productId, serviceStart, toOffsetDateTime(serviceStart));
    }

    private PolicySnapshot resolveBookingSnapshotInternal(Long tenantId,
                                                          Long productId,
                                                          LocalDateTime serviceStart,
                                                          OffsetDateTime policyEvaluationTime) {
        if (tenantId == null || serviceStart == null) {
            return null;
        }
        OffsetDateTime evaluationTime = policyEvaluationTime != null ? policyEvaluationTime : OffsetDateTime.now();
        CancellationPolicy policy = findApplicablePolicy(tenantId, productId, evaluationTime).orElse(null);
        if (policy == null) {
            return null;
        }

        LocalDateTime cutoffAt = serviceStart.minusDays(cutoffDays(policy));
        boolean beforeCutoff = !evaluationTime.toLocalDateTime().isAfter(cutoffAt);
        String releaseType = beforeCutoff ? policy.getBeforeCutoffReleaseType() : policy.getAfterCutoffReleaseType();
        BigDecimal releaseValue = beforeCutoff ? valueOrZero(policy.getBeforeCutoffReleaseValue()) : valueOrZero(policy.getAfterCutoffReleaseValue());
        boolean allowCashRefund = beforeCutoff ? Boolean.TRUE.equals(policy.getBeforeCutoffAllowCashRefund()) : Boolean.TRUE.equals(policy.getAfterCutoffAllowCashRefund());
        boolean allowCustomerCredit = beforeCutoff ? Boolean.TRUE.equals(policy.getBeforeCutoffAllowCustomerCredit()) : Boolean.TRUE.equals(policy.getAfterCutoffAllowCustomerCredit());
        String defaultSettlementMode = beforeCutoff
                ? normalizeSettlementMode(policy.getBeforeCutoffDefaultSettlementMode())
                : normalizeSettlementMode(policy.getAfterCutoffDefaultSettlementMode());

        String policyText = buildBookingSnapshotText(
                serviceStart,
                cutoffAt,
                beforeCutoff,
                releaseType,
                releaseValue,
                allowCashRefund,
                allowCustomerCredit,
                defaultSettlementMode
        );
        String genericText = buildGenericPolicyText(policy);
        PolicyRuleSnapshot ruleSnapshot = toRuleSnapshot(policy);
        return new PolicySnapshot(
                policy.getId(),
                policyText,
                genericText,
                cutoffAt,
                defaultSettlementMode,
                objectMapper.valueToTree(ruleSnapshot)
        );
    }

    public String describePolicyForAvailability(Long tenantId, Long productId) {
        if (tenantId == null) {
            return null;
        }
        return findApplicablePolicy(tenantId, productId)
                .map(this::buildGenericPolicyText)
                .orElse(null);
    }

    private Optional<CancellationPolicy> findApplicablePolicy(Long tenantId, Long productId) {
        return findApplicablePolicy(tenantId, productId, OffsetDateTime.now());
    }

    private Optional<CancellationPolicy> findApplicablePolicy(Long tenantId, Long productId, OffsetDateTime effectiveAt) {
        OffsetDateTime evaluationPoint = effectiveAt != null ? effectiveAt : OffsetDateTime.now();
        return repo.findByTenantIdAndActiveTrueOrderByPriorityDescIdDesc(tenantId).stream()
                .filter(policy -> isEffective(policy, evaluationPoint))
                .filter(policy -> appliesToProduct(policy, productId))
                .sorted(Comparator
                        .comparing((CancellationPolicy policy) -> isProductScope(policy, productId)).reversed()
                        .thenComparing(CancellationPolicy::getPriority, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CancellationPolicy::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst();
    }

    public ReservationCancellationEvaluation evaluateReservation(Reservation reservation, String settlementMode) {
        if (reservation == null) {
            throw new IllegalArgumentException("reservation is required");
        }
        BigDecimal cancelledAmount = valueOrZero(reservation.getGrossAmount());
        if (cancelledAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return new ReservationCancellationEvaluation(
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "NONE",
                    null,
                    null,
                    null
            );
        }

        PolicyRuleSnapshot snapshot = resolveRuleSnapshot(reservation);
        if (snapshot == null) {
            return new ReservationCancellationEvaluation(
                    cancelledAmount,
                    BigDecimal.ZERO,
                    cancelledAmount,
                    "NONE",
                    null,
                    null,
                    null
            );
        }

        LocalDateTime serviceStart = reservation.getStartsAt();
        LocalDateTime cutoffAt = serviceStart != null ? serviceStart.minusDays(snapshot.cutoffDaysBeforeStart()) : null;
        boolean beforeCutoff = cutoffAt != null && !LocalDateTime.now().isAfter(cutoffAt);
        String releaseType = beforeCutoff ? snapshot.beforeCutoffReleaseType() : snapshot.afterCutoffReleaseType();
        BigDecimal releaseValue = beforeCutoff ? valueOrZero(snapshot.beforeCutoffReleaseValue()) : valueOrZero(snapshot.afterCutoffReleaseValue());
        boolean allowCashRefund = beforeCutoff && snapshot.beforeCutoffAllowCashRefund()
                || !beforeCutoff && snapshot.afterCutoffAllowCashRefund();
        boolean allowCustomerCredit = beforeCutoff && snapshot.beforeCutoffAllowCustomerCredit()
                || !beforeCutoff && snapshot.afterCutoffAllowCustomerCredit();
        String defaultSettlementMode = beforeCutoff
                ? normalizeSettlementMode(snapshot.beforeCutoffDefaultSettlementMode())
                : normalizeSettlementMode(snapshot.afterCutoffDefaultSettlementMode());

        BigDecimal releasedAmount = calculateReleasedAmount(cancelledAmount, releaseType, releaseValue);
        BigDecimal penaltyAmount = money(cancelledAmount.subtract(releasedAmount));
        String resolvedSettlementMode = resolveSettlementMode(settlementMode, releasedAmount, allowCashRefund, allowCustomerCredit, defaultSettlementMode);
        BigDecimal refundAmount = "CASH_REFUND".equals(resolvedSettlementMode) ? releasedAmount : BigDecimal.ZERO;
        BigDecimal creditAmount = "CUSTOMER_CREDIT".equals(resolvedSettlementMode) ? releasedAmount : BigDecimal.ZERO;

        return new ReservationCancellationEvaluation(
                money(cancelledAmount),
                money(releasedAmount),
                money(penaltyAmount),
                resolvedSettlementMode,
                cutoffAt,
                snapshot.policyId(),
                beforeCutoff ? "BEFORE_CUTOFF" : "AFTER_CUTOFF"
        );
    }

    private boolean isEffective(CancellationPolicy policy, OffsetDateTime now) {
        if (policy.getEffectiveFrom() != null && policy.getEffectiveFrom().isAfter(now)) {
            return false;
        }
        if (policy.getEffectiveTo() != null && policy.getEffectiveTo().isBefore(now)) {
            return false;
        }
        return true;
    }

    private boolean appliesToProduct(CancellationPolicy policy, Long productId) {
        String scopeType = normalizeScopeType(policy.getScopeType());
        if ("TENANT".equals(scopeType)) {
            return true;
        }
        return productId != null && productId.equals(policy.getScopeId());
    }

    private boolean isProductScope(CancellationPolicy policy, Long productId) {
        return "PRODUCT".equals(normalizeScopeType(policy.getScopeType()))
                && productId != null
                && productId.equals(policy.getScopeId());
    }

    private String buildGenericPolicyText(CancellationPolicy policy) {
        String beforeText = describeReleaseOutcome(
                normalizeReleaseType(policy.getBeforeCutoffReleaseType()),
                valueOrZero(policy.getBeforeCutoffReleaseValue()),
                Boolean.TRUE.equals(policy.getBeforeCutoffAllowCashRefund()),
                Boolean.TRUE.equals(policy.getBeforeCutoffAllowCustomerCredit())
        );
        String afterText = describeReleaseOutcome(
                normalizeReleaseType(policy.getAfterCutoffReleaseType()),
                valueOrZero(policy.getAfterCutoffReleaseValue()),
                Boolean.TRUE.equals(policy.getAfterCutoffAllowCashRefund()),
                Boolean.TRUE.equals(policy.getAfterCutoffAllowCustomerCredit())
        );
        return "Cancellation up to " + cutoffDays(policy) + " days before start: " + beforeText
                + ". After that: " + afterText + ".";
    }

    private String buildBookingSnapshotText(LocalDateTime serviceStart,
                                            LocalDateTime cutoffAt,
                                            boolean beforeCutoff,
                                            String releaseType,
                                            BigDecimal releaseValue,
                                            boolean allowCashRefund,
                                            boolean allowCustomerCredit,
                                            String defaultSettlementMode) {
        String outcome = describeReleaseOutcome(releaseType, releaseValue, allowCashRefund, allowCustomerCredit);
        if (beforeCutoff) {
            StringBuilder text = new StringBuilder("Free cancellation until ")
                    .append(cutoffAt.format(SNAPSHOT_FORMATTER))
                    .append(". If cancelled before then: ")
                    .append(outcome)
                    .append(".");
            if (StringUtils.hasText(defaultSettlementMode)) {
                text.append(" Default settlement mode: ")
                        .append(defaultSettlementMode.toLowerCase(Locale.ROOT).replace('_', ' '))
                        .append(".");
            }
            text.append(" Service starts at ").append(serviceStart.format(SNAPSHOT_FORMATTER)).append(".");
            return text.toString();
        }
        return "This booking is already inside the cancellation cutoff. Current cancellation outcome: "
                + outcome
                + ". Service starts at " + serviceStart.format(SNAPSHOT_FORMATTER) + ".";
    }

    private String describeReleaseOutcome(String releaseType,
                                          BigDecimal releaseValue,
                                          boolean allowCashRefund,
                                          boolean allowCustomerCredit) {
        String normalizedReleaseType = normalizeReleaseType(releaseType);
        if ("NONE".equals(normalizedReleaseType)) {
            return "no refund or booking credit";
        }

        String releaseAmountText = switch (normalizedReleaseType) {
            case "FULL" -> "full amount";
            case "PERCENT" -> moneyText(releaseValue) + "% of the booking value";
            case "FIXED" -> moneyText(releaseValue) + " in booking value";
            default -> "released value";
        };

        if (allowCashRefund && allowCustomerCredit) {
            return releaseAmountText + " can be returned as cash refund or kept as booking credit";
        }
        if (allowCashRefund) {
            return releaseAmountText + " is returned as cash refund";
        }
        if (allowCustomerCredit) {
            return releaseAmountText + " is kept as reusable booking credit";
        }
        return releaseAmountText + " is released";
    }

    private void normalizeAndValidate(CancellationPolicy policy) {
        if (policy.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (!StringUtils.hasText(policy.getName())) {
            throw new IllegalArgumentException("name is required");
        }
        policy.setName(policy.getName().trim());
        policy.setDescription(normalizeNullable(policy.getDescription()));
        policy.setScopeType(normalizeScopeType(policy.getScopeType()));
        policy.setBeforeCutoffReleaseType(normalizeReleaseType(policy.getBeforeCutoffReleaseType()));
        policy.setAfterCutoffReleaseType(normalizeReleaseType(policy.getAfterCutoffReleaseType()));
        policy.setBeforeCutoffDefaultSettlementMode(normalizeSettlementMode(policy.getBeforeCutoffDefaultSettlementMode()));
        policy.setAfterCutoffDefaultSettlementMode(normalizeSettlementMode(policy.getAfterCutoffDefaultSettlementMode()));

        if ("TENANT".equals(policy.getScopeType())) {
            policy.setScopeId(null);
        } else if (policy.getScopeId() == null) {
            throw new IllegalArgumentException("scopeId is required for PRODUCT scope");
        }

        if (policy.getCutoffDaysBeforeStart() == null || policy.getCutoffDaysBeforeStart() < 0) {
            throw new IllegalArgumentException("cutoffDaysBeforeStart must be >= 0");
        }
        policy.setBeforeCutoffReleaseValue(normalizeReleaseValue(policy.getBeforeCutoffReleaseValue(), policy.getBeforeCutoffReleaseType()));
        policy.setAfterCutoffReleaseValue(normalizeReleaseValue(policy.getAfterCutoffReleaseValue(), policy.getAfterCutoffReleaseType()));

        if (policy.getEffectiveFrom() != null && policy.getEffectiveTo() != null
                && policy.getEffectiveTo().isBefore(policy.getEffectiveFrom())) {
            throw new IllegalArgumentException("effectiveTo must be >= effectiveFrom");
        }

        if (policy.getActive() == null) {
            policy.setActive(Boolean.TRUE);
        }
        if (policy.getPriority() == null) {
            policy.setPriority(100);
        }
        if (policy.getBeforeCutoffAllowCashRefund() == null) {
            policy.setBeforeCutoffAllowCashRefund(Boolean.TRUE);
        }
        if (policy.getBeforeCutoffAllowCustomerCredit() == null) {
            policy.setBeforeCutoffAllowCustomerCredit(Boolean.FALSE);
        }
        if (policy.getAfterCutoffAllowCashRefund() == null) {
            policy.setAfterCutoffAllowCashRefund(Boolean.FALSE);
        }
        if (policy.getAfterCutoffAllowCustomerCredit() == null) {
            policy.setAfterCutoffAllowCustomerCredit(Boolean.FALSE);
        }
    }

    private PolicyRuleSnapshot resolveRuleSnapshot(Reservation reservation) {
        JsonNode snapshotNode = reservation.getCancellationPolicySnapshot();
        if (snapshotNode != null && !snapshotNode.isNull() && !snapshotNode.isMissingNode()) {
            try {
                return objectMapper.treeToValue(snapshotNode, PolicyRuleSnapshot.class);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to read reservation cancellation policy snapshot", ex);
            }
        }
        if (reservation.getCancellationPolicyId() == null) {
            return null;
        }
        return repo.findById(reservation.getCancellationPolicyId())
                .map(this::toRuleSnapshot)
                .orElse(null);
    }

    private PolicyRuleSnapshot toRuleSnapshot(CancellationPolicy policy) {
        return new PolicyRuleSnapshot(
                policy.getId(),
                cutoffDays(policy),
                normalizeReleaseType(policy.getBeforeCutoffReleaseType()),
                valueOrZero(policy.getBeforeCutoffReleaseValue()),
                Boolean.TRUE.equals(policy.getBeforeCutoffAllowCashRefund()),
                Boolean.TRUE.equals(policy.getBeforeCutoffAllowCustomerCredit()),
                normalizeSettlementMode(policy.getBeforeCutoffDefaultSettlementMode()),
                normalizeReleaseType(policy.getAfterCutoffReleaseType()),
                valueOrZero(policy.getAfterCutoffReleaseValue()),
                Boolean.TRUE.equals(policy.getAfterCutoffAllowCashRefund()),
                Boolean.TRUE.equals(policy.getAfterCutoffAllowCustomerCredit()),
                normalizeSettlementMode(policy.getAfterCutoffDefaultSettlementMode())
        );
    }

    private BigDecimal calculateReleasedAmount(BigDecimal cancelledAmount, String releaseType, BigDecimal releaseValue) {
        String normalizedReleaseType = normalizeReleaseType(releaseType);
        BigDecimal releasedAmount = switch (normalizedReleaseType) {
            case "FULL" -> cancelledAmount;
            case "PERCENT" -> cancelledAmount.multiply(valueOrZero(releaseValue))
                    .divide(new BigDecimal("100"), 8, java.math.RoundingMode.HALF_UP);
            case "FIXED" -> valueOrZero(releaseValue);
            case "NONE" -> BigDecimal.ZERO;
            default -> BigDecimal.ZERO;
        };
        if (releasedAmount.compareTo(BigDecimal.ZERO) < 0) {
            releasedAmount = BigDecimal.ZERO;
        }
        if (releasedAmount.compareTo(cancelledAmount) > 0) {
            releasedAmount = cancelledAmount;
        }
        return money(releasedAmount);
    }

    private String resolveSettlementMode(String requestedSettlementMode,
                                         BigDecimal releasedAmount,
                                         boolean allowCashRefund,
                                         boolean allowCustomerCredit,
                                         String defaultSettlementMode) {
        if (releasedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return "NONE";
        }
        String normalizedRequested = normalizeSettlementMode(requestedSettlementMode);
        if (normalizedRequested != null) {
            validateSettlementAllowed(normalizedRequested, allowCashRefund, allowCustomerCredit);
            return normalizedRequested;
        }
        if (defaultSettlementMode != null) {
            validateSettlementAllowed(defaultSettlementMode, allowCashRefund, allowCustomerCredit);
            return defaultSettlementMode;
        }
        if (allowCashRefund) {
            return "CASH_REFUND";
        }
        if (allowCustomerCredit) {
            return "CUSTOMER_CREDIT";
        }
        throw new IllegalStateException("Cancellation policy releases value but no settlement mode is allowed");
    }

    private void validateSettlementAllowed(String settlementMode,
                                           boolean allowCashRefund,
                                           boolean allowCustomerCredit) {
        if ("CASH_REFUND".equals(settlementMode) && !allowCashRefund) {
            throw new IllegalArgumentException("CASH_REFUND is not allowed by cancellation policy");
        }
        if ("CUSTOMER_CREDIT".equals(settlementMode) && !allowCustomerCredit) {
            throw new IllegalArgumentException("CUSTOMER_CREDIT is not allowed by cancellation policy");
        }
    }

    private String normalizeScopeType(String scopeType) {
        if (!StringUtils.hasText(scopeType)) {
            return "TENANT";
        }
        String normalized = scopeType.trim().toUpperCase(Locale.ROOT);
        if (!"TENANT".equals(normalized) && !"PRODUCT".equals(normalized)) {
            throw new IllegalArgumentException("scopeType must be TENANT or PRODUCT");
        }
        return normalized;
    }

    private String normalizeReleaseType(String releaseType) {
        String normalized = StringUtils.hasText(releaseType)
                ? releaseType.trim().toUpperCase(Locale.ROOT)
                : "FULL";
        if (!List.of("FULL", "PERCENT", "FIXED", "NONE").contains(normalized)) {
            throw new IllegalArgumentException("releaseType must be FULL, PERCENT, FIXED, or NONE");
        }
        return normalized;
    }

    private String normalizeSettlementMode(String settlementMode) {
        if (!StringUtils.hasText(settlementMode)) {
            return null;
        }
        String normalized = settlementMode.trim().toUpperCase(Locale.ROOT);
        if (!"CASH_REFUND".equals(normalized) && !"CUSTOMER_CREDIT".equals(normalized)) {
            throw new IllegalArgumentException("settlementMode must be CASH_REFUND or CUSTOMER_CREDIT");
        }
        return normalized;
    }

    private BigDecimal normalizeReleaseValue(BigDecimal releaseValue, String releaseType) {
        BigDecimal value = valueOrZero(releaseValue);
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("releaseValue must be >= 0");
        }
        if ("FULL".equals(releaseType)) {
            return new BigDecimal("100");
        }
        if ("NONE".equals(releaseType)) {
            return BigDecimal.ZERO;
        }
        if ("PERCENT".equals(releaseType) && value.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("PERCENT releaseValue must be <= 100");
        }
        return value;
    }

    private int cutoffDays(CancellationPolicy policy) {
        return policy.getCutoffDaysBeforeStart() != null ? policy.getCutoffDaysBeforeStart() : 0;
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private String moneyText(BigDecimal value) {
        return valueOrZero(value).stripTrailingZeros().toPlainString();
    }

    private String normalizeNullable(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private BigDecimal money(BigDecimal value) {
        return valueOrZero(value).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return OffsetDateTime.now();
        }
        return localDateTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    public record PolicySnapshot(Long policyId,
                                 String bookingPolicyText,
                                 String genericPolicyText,
                                 LocalDateTime cutoffAt,
                                 String defaultSettlementMode,
                                 JsonNode ruleSnapshot) {
    }

    public record PolicyRuleSnapshot(Long policyId,
                                     Integer cutoffDaysBeforeStart,
                                     String beforeCutoffReleaseType,
                                     BigDecimal beforeCutoffReleaseValue,
                                     boolean beforeCutoffAllowCashRefund,
                                     boolean beforeCutoffAllowCustomerCredit,
                                     String beforeCutoffDefaultSettlementMode,
                                     String afterCutoffReleaseType,
                                     BigDecimal afterCutoffReleaseValue,
                                     boolean afterCutoffAllowCashRefund,
                                     boolean afterCutoffAllowCustomerCredit,
                                     String afterCutoffDefaultSettlementMode) {
    }

    public record ReservationCancellationEvaluation(BigDecimal cancelledAmount,
                                                    BigDecimal releasedAmount,
                                                    BigDecimal penaltyAmount,
                                                    String settlementMode,
                                                    LocalDateTime cutoffAt,
                                                    Long policyId,
                                                    String policyPhase) {
    }
}
