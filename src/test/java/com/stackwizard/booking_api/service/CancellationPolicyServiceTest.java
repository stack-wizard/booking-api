package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CancellationPolicy;
import com.stackwizard.booking_api.repository.CancellationPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancellationPolicyServiceTest {

    @Mock
    private CancellationPolicyRepository repo;

    private CancellationPolicyService service;

    @BeforeEach
    void setUp() {
        service = new CancellationPolicyService(repo);
    }

    @Test
    void resolveBookingSnapshotPrefersProductPolicyAndBuildsFrontendText() {
        Long tenantId = 7L;
        Long productId = 11L;
        LocalDateTime serviceStart = LocalDateTime.now().plusDays(20).withHour(9).withMinute(0).withSecond(0).withNano(0);

        CancellationPolicy tenantPolicy = basePolicy();
        tenantPolicy.setId(10L);
        tenantPolicy.setScopeType("TENANT");
        tenantPolicy.setPriority(200);
        tenantPolicy.setCutoffDaysBeforeStart(14);

        CancellationPolicy productPolicy = basePolicy();
        productPolicy.setId(20L);
        productPolicy.setScopeType("PRODUCT");
        productPolicy.setScopeId(productId);
        productPolicy.setPriority(100);
        productPolicy.setCutoffDaysBeforeStart(7);
        productPolicy.setBeforeCutoffAllowCustomerCredit(true);

        when(repo.findByTenantIdAndActiveTrueOrderByPriorityDescIdDesc(tenantId))
                .thenReturn(List.of(tenantPolicy, productPolicy));

        CancellationPolicyService.PolicySnapshot snapshot = service.resolveBookingSnapshot(tenantId, productId, serviceStart);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.policyId()).isEqualTo(20L);
        assertThat(snapshot.cutoffAt()).isEqualTo(serviceStart.minusDays(7));
        assertThat(snapshot.defaultSettlementMode()).isEqualTo("CASH_REFUND");
        assertThat(snapshot.bookingPolicyText()).contains("Free cancellation until");
        assertThat(snapshot.bookingPolicyText()).contains("cash refund or kept as booking credit");
        assertThat(snapshot.genericPolicyText()).contains("Cancellation up to 7 days before start");
    }

    @Test
    void describePolicyForAvailabilityReturnsTenantPolicySummary() {
        Long tenantId = 7L;

        CancellationPolicy tenantPolicy = basePolicy();
        tenantPolicy.setId(10L);
        tenantPolicy.setScopeType("TENANT");
        tenantPolicy.setCutoffDaysBeforeStart(14);
        tenantPolicy.setAfterCutoffReleaseType("NONE");
        tenantPolicy.setAfterCutoffReleaseValue(BigDecimal.ZERO);
        tenantPolicy.setAfterCutoffAllowCashRefund(false);
        tenantPolicy.setAfterCutoffAllowCustomerCredit(false);

        when(repo.findByTenantIdAndActiveTrueOrderByPriorityDescIdDesc(tenantId))
                .thenReturn(List.of(tenantPolicy));

        String description = service.describePolicyForAvailability(tenantId, 99L);

        assertThat(description).isEqualTo(
                "Cancellation up to 14 days before start: full amount is returned as cash refund. After that: no refund or booking credit."
        );
    }

    @Test
    void saveRejectsProductScopeWithoutScopeId() {
        CancellationPolicy policy = basePolicy();
        policy.setScopeType("PRODUCT");
        policy.setScopeId(null);

        assertThatThrownBy(() -> service.save(policy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scopeId is required for PRODUCT scope");
    }

    private CancellationPolicy basePolicy() {
        return CancellationPolicy.builder()
                .tenantId(7L)
                .name("Default cancellation")
                .active(true)
                .priority(100)
                .scopeType("TENANT")
                .cutoffDaysBeforeStart(14)
                .beforeCutoffReleaseType("FULL")
                .beforeCutoffReleaseValue(BigDecimal.ZERO)
                .beforeCutoffAllowCashRefund(true)
                .beforeCutoffAllowCustomerCredit(false)
                .beforeCutoffDefaultSettlementMode("CASH_REFUND")
                .afterCutoffReleaseType("NONE")
                .afterCutoffReleaseValue(BigDecimal.ZERO)
                .afterCutoffAllowCashRefund(false)
                .afterCutoffAllowCustomerCredit(false)
                .build();
    }
}
