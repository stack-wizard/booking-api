package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.PriceProfile;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceListEntryResolverTest {

    @Mock
    private PriceListEntryRepository priceListRepo;

    @Test
    void findEffectiveForProductUomOnDatePrefersTypedEntryButKeepsGenericFallbackForOtherWindows() {
        PriceListEntryResolver resolver = new PriceListEntryResolver(priceListRepo);
        PriceListEntry genericMorning = entry(
                "HALFDAY",
                "70.00",
                LocalTime.of(9, 0),
                LocalTime.of(13, 0),
                null
        );
        PriceListEntry genericAfternoon = entry(
                "HALFDAY",
                "75.00",
                LocalTime.of(14, 0),
                LocalTime.of(18, 0),
                null
        );
        PriceListEntry walkinMorning = entry(
                "HALFDAY",
                "60.00",
                LocalTime.of(9, 0),
                LocalTime.of(13, 0),
                ReservationRequest.Type.WALKIN
        );

        when(priceListRepo.findCandidatesForProductUomOnDate(
                15L,
                "HALFDAY",
                "EUR",
                3L,
                LocalDate.of(2026, 7, 12),
                ReservationRequest.Type.WALKIN
        )).thenReturn(List.of(genericMorning, genericAfternoon, walkinMorning));

        List<PriceListEntry> resolved = resolver.findEffectiveForProductUomOnDate(
                15L,
                "HALFDAY",
                "EUR",
                3L,
                LocalDate.of(2026, 7, 12),
                ReservationRequest.Type.WALKIN
        );

        assertThat(resolved)
                .extracting(PriceListEntry::getPrice)
                .containsExactly(new BigDecimal("60.00"), new BigDecimal("75.00"));
    }

    private PriceListEntry entry(String uom,
                                 String amount,
                                 LocalTime startTime,
                                 LocalTime endTime,
                                 ReservationRequest.Type requestType) {
        return PriceListEntry.builder()
                .productId(15L)
                .uom(uom)
                .price(new BigDecimal(amount))
                .startTime(startTime)
                .endTime(endTime)
                .priceProfile(PriceProfile.builder()
                        .id(requestType == null ? 1L : 2L)
                        .tenantId(3L)
                        .currency("EUR")
                        .reservationRequestType(requestType)
                        .build())
                .build();
    }
}
