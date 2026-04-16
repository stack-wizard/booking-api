package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.CheckinReadinessDto;
import com.stackwizard.booking_api.dto.CheckinResultDto;
import com.stackwizard.booking_api.dto.CheckoutInvoiceWarningDto;
import com.stackwizard.booking_api.dto.CheckoutReadinessDto;
import com.stackwizard.booking_api.dto.InvoiceCheckoutGateResult;
import com.stackwizard.booking_api.exception.CheckoutBlockedException;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationStayServiceTest {

    @Mock
    private ReservationRequestRepository requestRepo;
    @Mock
    private ReservationRepository reservationRepo;
    @Mock
    private InvoiceService invoiceService;

    @InjectMocks
    private ReservationStayService stayService;

    @Test
    void getCheckoutReadinessWhenNotCheckedInReturnsNotReady() {
        ReservationRequest request = ReservationRequest.builder()
                .id(1L)
                .tenantId(2L)
                .status(ReservationRequest.Status.FINALIZED)
                .build();
        when(requestRepo.findById(1L)).thenReturn(Optional.of(request));

        CheckoutReadinessDto dto = stayService.getCheckoutReadiness(1L);

        assertThat(dto.isReady()).isFalse();
        assertThat(dto.getBlockers()).isNotEmpty();
        verify(invoiceService, never()).evaluateCheckoutGateForReservationRequest(anyLong());
    }

    @Test
    void getCheckinReadinessWhenFinalizedAndConfirmedIsEligible() {
        long requestId = 3L;
        ReservationRequest request = ReservationRequest.builder()
                .id(requestId)
                .tenantId(1L)
                .status(ReservationRequest.Status.FINALIZED)
                .expiresAt(null)
                .build();
        Reservation reservation = Reservation.builder()
                .id(20L)
                .tenantId(1L)
                .requestType(ReservationRequest.Type.EXTERNAL)
                .status("CONFIRMED")
                .build();
        when(requestRepo.findById(requestId)).thenReturn(Optional.of(request));
        when(reservationRepo.findByRequestId(requestId)).thenReturn(List.of(reservation));

        CheckinReadinessDto dto = stayService.getCheckinReadiness(requestId);

        assertThat(dto.isEligible()).isTrue();
        assertThat(dto.getIssues()).isEmpty();
    }

    @Test
    void checkOutThrowsWhenGateHasBlockers() {
        ReservationRequest request = ReservationRequest.builder()
                .id(1L)
                .tenantId(2L)
                .status(ReservationRequest.Status.CHECKED_IN)
                .build();
        when(requestRepo.findById(1L)).thenReturn(Optional.of(request));
        when(invoiceService.evaluateCheckoutGateForReservationRequest(1L))
                .thenReturn(new InvoiceCheckoutGateResult(List.of("Invoice X is still a draft"), List.of()));

        assertThatThrownBy(() -> stayService.checkOut(1L))
                .isInstanceOf(CheckoutBlockedException.class)
                .extracting(ex -> ((CheckoutBlockedException) ex).getBlockers())
                .asList()
                .containsExactly("Invoice X is still a draft");

        verify(requestRepo, never()).save(any());
    }

    @Test
    void checkOutReturnsWarningsWhenSuccessful() {
        ReservationRequest request = ReservationRequest.builder()
                .id(1L)
                .tenantId(2L)
                .status(ReservationRequest.Status.CHECKED_IN)
                .build();
        Reservation res = Reservation.builder()
                .id(9L)
                .tenantId(2L)
                .status("CONFIRMED")
                .build();
        when(requestRepo.findById(1L)).thenReturn(Optional.of(request));
        when(invoiceService.evaluateCheckoutGateForReservationRequest(1L))
                .thenReturn(new InvoiceCheckoutGateResult(
                        List.of(),
                        List.of(CheckoutInvoiceWarningDto.builder()
                                .invoiceId(3L)
                                .invoiceNumber("I-1")
                                .invoiceType("INVOICE")
                                .operaPostingStatus("NOT_POSTED")
                                .message("not posted")
                                .build())));
        when(reservationRepo.findByRequestId(1L)).thenReturn(List.of(res));
        when(requestRepo.save(any(ReservationRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservationRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = stayService.checkOut(1L);

        assertThat(result.getStatus()).isEqualTo("CHECKED_OUT");
        assertThat(result.getWarnings()).hasSize(1);
        assertThat(result.getWarnings().get(0).getInvoiceId()).isEqualTo(3L);
        assertThat(request.getStatus()).isEqualTo(ReservationRequest.Status.CHECKED_OUT);
        assertThat(res.getStatus()).isEqualTo("CHECKED_OUT");
    }

    @Test
    void checkInFromFinalizedStornosDepositCreatesDraftFinalInvoiceAllocatesAndSetsStatuses() {
        long requestId = 5L;
        ReservationRequest request = ReservationRequest.builder()
                .id(requestId)
                .tenantId(1L)
                .status(ReservationRequest.Status.FINALIZED)
                .expiresAt(null)
                .build();
        Reservation reservation = Reservation.builder()
                .id(10L)
                .tenantId(1L)
                .requestType(ReservationRequest.Type.EXTERNAL)
                .status("CONFIRMED")
                .build();
        Invoice deposit = mock(Invoice.class);
        when(deposit.getId()).thenReturn(33L);
        when(deposit.getInvoiceType()).thenReturn(InvoiceType.DEPOSIT);
        when(deposit.getStornoId()).thenReturn(null);
        Invoice finalInvoice = mock(Invoice.class);
        when(finalInvoice.getId()).thenReturn(200L);

        when(requestRepo.findById(requestId)).thenReturn(Optional.of(request));
        when(reservationRepo.findByRequestId(requestId)).thenReturn(List.of(reservation));
        when(invoiceService.findByRequestId(requestId)).thenReturn(List.of(deposit));
        when(invoiceService.hasReversalChildForSourceInvoice(33L, InvoiceType.DEPOSIT)).thenReturn(false);
        when(invoiceService.createDraftForFinalizedRequest(requestId)).thenReturn(finalInvoice);
        when(requestRepo.save(any(ReservationRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservationRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceService.findPrimaryInvoiceForReservationRequest(requestId)).thenReturn(Optional.of(finalInvoice));

        CheckinResultDto result = stayService.checkIn(requestId);

        assertThat(result.getFinalInvoiceId()).isEqualTo(200L);
        assertThat(request.getStatus()).isEqualTo(ReservationRequest.Status.CHECKED_IN);
        assertThat(reservation.getStatus()).isEqualTo("CHECKED_IN");
        verify(invoiceService).createStornoInvoice(33L);
        verify(invoiceService).createDraftForFinalizedRequest(requestId);
        verify(invoiceService).allocateReleasedDepositPaymentsToFinalRequestInvoice(requestId);
    }

    @Test
    void checkInSkipsStornoWhenCreditNoteAlreadyReferencesDeposit() {
        long requestId = 5L;
        ReservationRequest request = ReservationRequest.builder()
                .id(requestId)
                .tenantId(1L)
                .status(ReservationRequest.Status.FINALIZED)
                .expiresAt(null)
                .build();
        Reservation reservation = Reservation.builder()
                .id(10L)
                .tenantId(1L)
                .requestType(ReservationRequest.Type.EXTERNAL)
                .status("CONFIRMED")
                .build();
        Invoice deposit = mock(Invoice.class);
        when(deposit.getId()).thenReturn(33L);
        when(deposit.getInvoiceType()).thenReturn(InvoiceType.DEPOSIT);
        when(deposit.getStornoId()).thenReturn(null);
        Invoice finalInvoice = mock(Invoice.class);
        when(finalInvoice.getId()).thenReturn(200L);

        when(requestRepo.findById(requestId)).thenReturn(Optional.of(request));
        when(reservationRepo.findByRequestId(requestId)).thenReturn(List.of(reservation));
        when(invoiceService.findByRequestId(requestId)).thenReturn(List.of(deposit));
        when(invoiceService.hasReversalChildForSourceInvoice(33L, InvoiceType.DEPOSIT)).thenReturn(true);
        when(invoiceService.createDraftForFinalizedRequest(requestId)).thenReturn(finalInvoice);
        when(requestRepo.save(any(ReservationRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservationRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceService.findPrimaryInvoiceForReservationRequest(requestId)).thenReturn(Optional.of(finalInvoice));

        stayService.checkIn(requestId);

        verify(invoiceService, never()).createStornoInvoice(anyLong());
        verify(invoiceService).createDraftForFinalizedRequest(requestId);
    }

    @Test
    void checkInWhenAlreadyCheckedInDoesNotCallStorno() {
        ReservationRequest request = ReservationRequest.builder()
                .id(1L)
                .tenantId(1L)
                .status(ReservationRequest.Status.CHECKED_IN)
                .build();
        when(requestRepo.findById(1L)).thenReturn(Optional.of(request));
        Invoice inv = org.mockito.Mockito.mock(Invoice.class);
        when(inv.getId()).thenReturn(99L);
        when(invoiceService.findPrimaryInvoiceForReservationRequest(1L)).thenReturn(Optional.of(inv));

        CheckinResultDto result = stayService.checkIn(1L);

        assertThat(result.getFinalInvoiceId()).isEqualTo(99L);
        verify(invoiceService, never()).createStornoInvoice(anyLong());
        verify(invoiceService, never()).createDraftForFinalizedRequest(anyLong());
    }
}
