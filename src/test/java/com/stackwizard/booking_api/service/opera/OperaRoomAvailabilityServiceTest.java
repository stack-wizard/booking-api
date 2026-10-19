package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperaRoomAvailabilityServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseReadsDailyRoomTypeCounts() throws Exception {
        ObjectNode response = mapper.readValue("""
                {
                  "hotelInventories": [{
                    "roomTypeInventories": [{
                      "code": "DBL",
                      "inventoryCounts": [
                        { "startDate": "2026-11-09", "endDate": "2026-11-09", "availableCount": 12 },
                        { "startDate": "2026-11-10", "endDate": "2026-11-10", "availableCount": 4 }
                      ]
                    }, {
                      "code": "sgl",
                      "inventoryCounts": [
                        { "startDate": "20261109", "availableCount": 2 }
                      ]
                    }]
                  }]
                }
                """, ObjectNode.class);
        Map<String, Map<LocalDate, Integer>> free = new HashMap<>();
        OperaRoomAvailabilityService.parse(response, free);

        assertThat(free).containsOnlyKeys("DBL", "SGL");
        assertThat(free.get("DBL")).containsEntry(LocalDate.of(2026, 11, 9), 12)
                .containsEntry(LocalDate.of(2026, 11, 10), 4);
        assertThat(free.get("SGL")).containsEntry(LocalDate.of(2026, 11, 9), 2);
        assertThat(OperaRoomAvailabilityService.minFree(free.get("DBL"))).isEqualTo(4);
    }
}
