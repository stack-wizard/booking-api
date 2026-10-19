package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.Booking;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperaBlockSyncServiceTest {

    @Test
    void blockCodeFromBookingId() {
        assertThat(OperaBlockSyncService.blockCode(Booking.builder()
                .id(42L)
                .dateFrom(java.time.LocalDate.of(2026, 11, 2))
                .build())).isEqualTo("2611AAAABQ");
    }

    @Test
    void extractBlockIdFromLocation() {
        assertThat(OperaBlockSyncService.extractBlockId(
                "https://host/blk/v1/hotels/DH/blocks/998877", null)).isEqualTo("998877");
    }

    @Test
    void extractBlockIdFromBody() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        body.putObject("blocks").putArray("blockInfo").addObject().putObject("block")
                .putArray("blockIdList").addObject().put("id", "555");
        assertThat(OperaBlockSyncService.extractBlockId(null, body)).isEqualTo("555");
    }
}
