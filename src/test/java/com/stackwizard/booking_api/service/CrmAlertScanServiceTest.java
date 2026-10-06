package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CrmAlert;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.SalesContract;
import com.stackwizard.booking_api.model.SalesPaymentMilestone;
import com.stackwizard.booking_api.repository.EventRepository;
import com.stackwizard.booking_api.repository.SalesContractRepository;
import com.stackwizard.booking_api.repository.SalesPaymentMilestoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrmAlertScanServiceTest {

    @Mock EventRepository eventRepo;
    @Mock SalesPaymentMilestoneRepository milestoneRepo;
    @Mock SalesContractRepository contractRepo;
    @Mock CrmAlertService alertService;

    CrmAlertScanService service;
    static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    @BeforeEach
    void setUp() {
        service = new CrmAlertScanService(eventRepo, milestoneRepo, contractRepo, alertService, 3, 3, 7, 3);
        when(alertService.raise(any())).thenReturn(true);
    }

    @Test
    void raisesOneAlertPerFactWithDateInDedupeKey() {
        Event tentative = Event.builder().id(1L).tenantId(2L).ownerUserId(7L).name("Kongres")
                .decisionDate(TODAY.plusDays(2)).build();
        Event noNumbers = Event.builder().id(2L).tenantId(2L).ownerUserId(8L).name("Gala")
                .guaranteeDueDate(TODAY.plusDays(1)).build();
        Event noBeo = Event.builder().id(3L).tenantId(2L).name("Seminar").dateFrom(TODAY.plusDays(5)).build();
        when(eventRepo.findDecisionDueBetween(TODAY, TODAY.plusDays(3))).thenReturn(List.of(tentative));
        when(eventRepo.findGuaranteeDueBetween(TODAY, TODAY.plusDays(3))).thenReturn(List.of(noNumbers));
        when(eventRepo.findDefiniteWithoutBeoStartingBetween(TODAY, TODAY.plusDays(7))).thenReturn(List.of(noBeo));
        SalesPaymentMilestone overdue = SalesPaymentMilestone.builder().id(70L).tenantId(2L).contractId(9L)
                .kind(SalesPaymentMilestone.Kind.DEPOSIT).dueDate(TODAY.minusDays(1)).amount(new BigDecimal("300.00")).build();
        when(milestoneRepo.findPlannedDueUntil(TODAY.plusDays(3))).thenReturn(List.of(overdue));
        when(contractRepo.findByIdAndTenantId(9L, 2L)).thenReturn(Optional.of(SalesContract.builder().id(9L).tenantId(2L)
                .eventId(3L).contractNumber("C2026-0001").currency("EUR").status(SalesContract.Status.SIGNED).build()));
        when(eventRepo.findByIdAndTenantId(3L, 2L)).thenReturn(Optional.of(noBeo));

        assertThat(service.scan(TODAY)).isEqualTo(4);

        ArgumentCaptor<CrmAlert> alerts = ArgumentCaptor.forClass(CrmAlert.class);
        verify(alertService, times(4)).raise(alerts.capture());
        assertThat(alerts.getAllValues()).extracting(CrmAlert::getDedupeKey).containsExactly(
                "DECISION:1:" + TODAY.plusDays(2),
                "GUARANTEE:2:" + TODAY.plusDays(1),
                "BEO:3:" + TODAY.plusDays(5),
                "MILESTONE:70:" + TODAY.minusDays(1));
        assertThat(alerts.getAllValues().get(0).getAssignedTo()).isEqualTo(7L);
        assertThat(alerts.getAllValues().get(3).getMessage()).contains("was due");
    }

    @Test
    void cancelledContractMilestonesAreSkipped() {
        when(milestoneRepo.findPlannedDueUntil(TODAY.plusDays(3))).thenReturn(List.of(SalesPaymentMilestone.builder()
                .id(70L).tenantId(2L).contractId(9L).kind(SalesPaymentMilestone.Kind.FINAL).dueDate(TODAY).build()));
        when(contractRepo.findByIdAndTenantId(9L, 2L)).thenReturn(Optional.of(SalesContract.builder().id(9L).tenantId(2L)
                .status(SalesContract.Status.CANCELLED).build()));
        assertThat(service.scan(TODAY)).isZero();
    }
}
