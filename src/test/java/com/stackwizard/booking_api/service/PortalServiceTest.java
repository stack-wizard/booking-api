package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.SalesDtos;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.PortalAccessToken;
import com.stackwizard.booking_api.model.SalesContract;
import com.stackwizard.booking_api.model.SalesQuote;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.BookingRepository;
import com.stackwizard.booking_api.repository.SalesContractDocumentRepository;
import com.stackwizard.booking_api.repository.SalesContractRepository;
import com.stackwizard.booking_api.repository.SalesQuoteRepository;
import com.stackwizard.booking_api.repository.SalesQuoteVersionRepository;
import com.stackwizard.booking_api.security.AuthUserAccessor;
import com.stackwizard.booking_api.security.PortalAccessContext;
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PortalServiceTest {

    @Mock PortalTokenService tokenService;
    @Mock AuthUserAccessor authUserAccessor;
    @Mock BookingRepository bookingRepo;
    @Mock CrmContactRepository contactRepo;
    @Mock CrmAccountRepository accountRepo;
    @Mock SalesQuoteRepository quoteRepo;
    @Mock SalesQuoteVersionRepository versionRepo;
    @Mock SalesQuoteService quoteService;
    @Mock SalesContractRepository contractRepo;
    @Mock SalesContractDocumentRepository documentRepo;
    @Mock SalesContractService contractService;
    @Mock SalesDocumentService documentService;
    @Mock InvoiceService invoiceService;
    @Mock InvoicePdfService invoicePdfService;

    PortalService service;
    Booking booking = Booking.builder().id(5L).tenantId(2L).accountId(3L).name("Pharma").status(Booking.Status.TENTATIVE)
            .dateFrom(LocalDate.now().plusDays(10)).dateTo(LocalDate.now().plusDays(11)).build();
    PortalAccessContext ctx = new PortalAccessContext(2L, Set.of(5L), null, PortalAccessContext.Mode.TOKEN, "t");

    @BeforeEach
    void setUp() {
        service = new PortalService(tokenService, authUserAccessor, bookingRepo, contactRepo, accountRepo, quoteRepo, versionRepo,
                quoteService, contractRepo, documentRepo, contractService, documentService, invoiceService, invoicePdfService);
        when(bookingRepo.findByIdAndTenantId(5L, 2L)).thenReturn(Optional.of(booking));
        when(contractRepo.findFirstByTenantIdAndBookingIdAndStatusNot(2L, 5L, SalesContract.Status.CANCELLED)).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void tokenResolvesToItsBookingOnly() {
        when(tokenService.requireValid("abc")).thenReturn(PortalAccessToken.builder().tenantId(2L).bookingId(5L).token("abc").build());
        PortalAccessContext resolved = service.fromToken("abc");
        assertThat(resolved.bookingIds()).containsExactly(5L);
        assertThatThrownBy(() -> service.booking(resolved, 6L)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void draftAndNeverSentQuotesAreHidden() {
        SalesQuote draft = quote(1L, SalesQuote.Status.DRAFT, 1);
        SalesQuote sent = quote(2L, SalesQuote.Status.SENT, 1);
        SalesQuote neverSent = quote(3L, SalesQuote.Status.SUPERSEDED, 0);
        when(quoteRepo.findByTenantIdAndBookingIdOrderByIdDesc(2L, 5L)).thenReturn(List.of(draft, sent, neverSent));

        PortalService.PortalBookingView view = service.booking(ctx, 5L);

        assertThat(view.quotes()).extracting(PortalService.PortalQuote::id).containsExactly(2L);
        assertThat(view.quotes().get(0).canDecide()).isTrue();
        assertThat(view.contract()).isNull();
    }

    @Test
    void acceptingRequiresNameAndScopedQuote() {
        SalesQuote sent = quote(2L, SalesQuote.Status.SENT, 1);
        when(quoteRepo.findByIdAndTenantId(2L, 2L)).thenReturn(Optional.of(sent));
        when(quoteRepo.findByTenantIdAndBookingIdOrderByIdDesc(2L, 5L)).thenReturn(List.of(sent));

        assertThatThrownBy(() -> service.decide(ctx, 2L, true, new SalesDtos.DecisionRequest(" ", null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(quoteService, never()).applyDecision(any(), anyBoolean(), anyString(), any(), anyBoolean());

        service.decide(ctx, 2L, true, new SalesDtos.DecisionRequest("Ana Horvat", "Looks good"));
        verify(quoteService).applyDecision(sent, true, "Ana Horvat", "Looks good", true);

        PortalAccessContext other = new PortalAccessContext(2L, Set.of(6L), null, PortalAccessContext.Mode.TOKEN, "x");
        assertThatThrownBy(() -> service.decide(other, 2L, false, null)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void platformUserSeesBookingsOfLinkedContacts() {
        TenantContext.setTenantId(2L);
        UUID platformUser = UUID.randomUUID();
        AppUser user = new AppUser();
        user.setPlatformUserId(platformUser);
        when(authUserAccessor.currentAppUser()).thenReturn(Optional.of(user));
        when(contactRepo.findByTenantIdAndPlatformUserId(2L, platformUser))
                .thenReturn(List.of(CrmContact.builder().id(11L).accountId(3L).build()));
        when(bookingRepo.findForPortal(eq(2L), eq(Set.of(11L)), any())).thenReturn(List.of(booking));

        PortalAccessContext resolved = service.fromPlatformUser();

        assertThat(resolved.mode()).isEqualTo(PortalAccessContext.Mode.PLATFORM_USER);
        assertThat(resolved.bookingIds()).containsExactly(5L);
        assertThat(resolved.contactId()).isEqualTo(11L);
    }

    @Test
    void platformUserWithoutContactIsForbidden() {
        TenantContext.setTenantId(2L);
        AppUser user = new AppUser();
        user.setPlatformUserId(UUID.randomUUID());
        when(authUserAccessor.currentAppUser()).thenReturn(Optional.of(user));
        when(contactRepo.findByTenantIdAndPlatformUserId(any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.fromPlatformUser()).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No client contact");
    }

    private static SalesQuote quote(Long id, SalesQuote.Status status, int version) {
        return SalesQuote.builder().id(id).tenantId(2L).bookingId(5L).quoteNumber("Q-" + id).status(status).version(version)
                .currency("EUR").validUntil(LocalDate.now().plusDays(5)).totalOffered(new BigDecimal("100"))
                .totalTax(new BigDecimal("20")).sentAt(OffsetDateTime.now()).build();
    }
}
