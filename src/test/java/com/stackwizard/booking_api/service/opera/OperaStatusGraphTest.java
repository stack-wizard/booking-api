package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.OperaCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OperaStatusGraphTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** DH flow: PRO → TEN/ALT/RGT, TEN → DEF/CXL, ALT → ACT/CXL, DEF → CXL. */
    private final OperaStatusGraph graph = new OperaStatusGraph(List.of(
            code("PRO", "Inquiry", "Inquiry", true, false, false, "TEN", "ALT", "RGT"),
            code("TEN", "NonDeductInv", "NonDeductInv", true, false, false, "DEF", "CXL"),
            code("ALT", "NonDeductInv", "NonDeductInv", false, true, false, "ACT", "CXL"),
            code("DEF", "DeductInv", "DeductInv", false, true, false, "CXL"),
            code("ACT", "Actual", "Actual", false, true, false),
            code("CXL", "Cancel", "Cancel", false, false, true),
            code("RGT", "Refused", "Cancel", false, false, true)));

    @Test
    void pathFollowsOperaFlow() {
        assertThat(graph.path("PRO", "DEF")).containsExactly("TEN", "DEF");
        assertThat(graph.path("PRO", "ACT")).containsExactly("ALT", "ACT");
        assertThat(graph.path("DEF", "ACT")).isEmpty();
        assertThat(graph.path("TEN", "TEN")).isEmpty();
        assertThat(graph.allows("TEN", "CXL")).isTrue();
        assertThat(graph.allows("DEF", "TEN")).isFalse();
    }

    @Test
    void newBlockStartsInNearestStartingStatus() {
        assertThat(graph.startingFor("PRO")).isEqualTo("PRO");
        assertThat(graph.startingFor("TEN")).isEqualTo("TEN");
        assertThat(graph.startingFor("DEF")).isEqualTo("TEN");
        assertThat(graph.startingFor("ACT")).isEqualTo("PRO");
    }

    @Test
    void phasesAndInventory() {
        assertThat(graph.phase("RGT")).isEqualTo(Booking.Status.CANCELLED);
        assertThat(graph.phase("TEN")).isEqualTo(Booking.Status.TENTATIVE);
        assertThat(graph.firstOfPhase(Booking.Status.DEFINITE)).contains("DEF");
        assertThat(graph.deducts("DEF")).isTrue();
        assertThat(graph.deducts("ACT")).isTrue();
        assertThat(graph.deducts("TEN")).isFalse();
        assertThat(graph.allowsPickup("DEF")).isTrue();
        assertThat(graph.returnsToInventory("CXL")).isTrue();
    }

    private static OperaCode code(String code, String room, String catering, boolean starting, boolean pickup,
                                  boolean release, String... next) {
        ObjectNode attrs = MAPPER.createObjectNode();
        attrs.put("roomStatusType", room);
        attrs.put("cateringStatusType", catering);
        attrs.put("starting", starting);
        attrs.put("allowPickup", pickup);
        attrs.put("returnToInventory", release);
        var array = attrs.putArray("next");
        for (String n : next) {
            array.add(n);
        }
        return OperaCode.builder().kind(OperaCode.Kind.BLOCK_STATUS).code(code).active(true).attrs(attrs).build();
    }
}
