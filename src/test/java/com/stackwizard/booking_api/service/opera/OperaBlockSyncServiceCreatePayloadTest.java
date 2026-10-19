package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.model.BookingOperaBlock;
import com.stackwizard.booking_api.model.BookingRoomBlock;
import com.stackwizard.booking_api.model.OperaCodeMap;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.repository.BookingFunctionRepository;
import com.stackwizard.booking_api.repository.BookingOperaBlockRepository;
import com.stackwizard.booking_api.repository.BookingRoomBlockNightRepository;
import com.stackwizard.booking_api.repository.BookingRoomBlockRepository;
import com.stackwizard.booking_api.repository.BookingStatusRepository;
import com.stackwizard.booking_api.repository.OperaCodeMapRepository;
import com.stackwizard.booking_api.repository.OperaCodeRepository;
import com.stackwizard.booking_api.repository.OperaHotelRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperaBlockSyncServiceCreatePayloadTest {

    @Mock OperaHotelRepository hotelRepo;
    @Mock BookingOperaBlockRepository blockRepo;
    @Mock BookingRoomBlockRepository roomBlockRepo;
    @Mock BookingRoomBlockNightRepository roomNightRepo;
    @Mock BookingFunctionRepository functionRepo;
    @Mock ReservationRepository reservationRepo;
    @Mock ReservationRequestRepository requestRepo;
    @Mock ResourceRepository resourceRepo;
    @Mock OperaCodeMapRepository mapRepo;
    @Mock OperaCodeRepository codeRepo;
    @Mock BookingStatusRepository statusRepo;
    @Mock OperaPostingClient client;
    @Mock OperaTenantConfigResolver configResolver;

    private OperaBlockSyncService service() {
        return new OperaBlockSyncService(hotelRepo, blockRepo, roomBlockRepo, roomNightRepo, functionRepo,
                reservationRepo, requestRepo, resourceRepo, mapRepo, codeRepo, statusRepo, client, configResolver);
    }

    @Test
    void resolveRatePlanCodeFromProfileMap() {
        OperaHotel hotel = OperaHotel.builder().id(9L).tenantId(2L).hotelCode("DH").build();
        BookingRoomBlock room = BookingRoomBlock.builder()
                .id(1L).roomResourceId(11L).ratePriceProfileId(42L).build();
        when(mapRepo.findByTenantIdAndOperaHotelIdAndKindAndLocalRef(2L, 9L, OperaCodeMap.Kind.RATE_CODE, "42"))
                .thenReturn(Optional.of(OperaCodeMap.builder().operaCode("FJBB").build()));

        assertThat(service().resolveRatePlanCode(hotel, List.of(room))).isEqualTo("FJBB");
    }

    @Test
    void resolveRatePlanCodeFailsWithoutMap() {
        OperaHotel hotel = OperaHotel.builder().id(9L).tenantId(2L).hotelCode("DH").build();
        BookingRoomBlock room = BookingRoomBlock.builder()
                .id(1L).roomResourceId(11L).ratePriceProfileId(42L).build();
        when(mapRepo.findByTenantIdAndOperaHotelIdAndKindAndLocalRef(2L, 9L, OperaCodeMap.Kind.RATE_CODE, "42"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().resolveRatePlanCode(hotel, List.of(room)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RATE_CODE");
    }

    @Test
    void resolveRatePlanCodeRequiresProfileOnPlacedRoom() {
        OperaHotel hotel = OperaHotel.builder().id(9L).tenantId(2L).hotelCode("DH").build();
        BookingRoomBlock room = BookingRoomBlock.builder().id(1L).roomResourceId(11L).build();

        assertThatThrownBy(() -> service().resolveRatePlanCode(hotel, List.of(room)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rate price profile");
    }

    @Test
    void roomRatePlanUpdateBodySetsMappedCodeAndClearsCateringOnly() {
        OperaHotel hotel = OperaHotel.builder().id(9L).tenantId(2L).hotelCode("DH").build();
        BookingOperaBlock row = BookingOperaBlock.builder().blockId("158786").build();

        JsonNode body = OperaBlockSyncService.roomRatePlanUpdateBody(hotel, row, "FJBB");
        JsonNode block = body.path("blocks").path(0);

        assertThat(block.path("hotelId").asText()).isEqualTo("DH");
        assertThat(block.path("blockIdList").path(0).path("id").asText()).isEqualTo("158786");
        assertThat(block.path("blockDetails").path("cateringOnlyBlock").asBoolean()).isFalse();
        assertThat(block.path("reservationDetails").path("ratePlanCode").path(0).path("ratePlanCode").asText())
                .isEqualTo("FJBB");
        assertThat(block.path("reservationDetails").path("ratePlanCode").path(0).path("primary").asBoolean()).isTrue();
        assertThat(block.path("reservationDetails").path("inventoryControl").asText()).isEqualTo("Elastic");
    }
}
