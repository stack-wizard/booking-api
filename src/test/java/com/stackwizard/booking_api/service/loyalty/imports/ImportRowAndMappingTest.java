package com.stackwizard.booking_api.service.loyalty.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.PmsReservation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImportRowAndMappingTest {
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void parsesCommonDateBooleanAndNumberFormats() {
        ObjectNode raw = om.createObjectNode()
                .put("d1", "2026-10-28").put("d2", "28.10.2026.").put("d3", "28/10/2026").put("d4", "28-OCT-26")
                .put("d5", "2026-10-28T10:15:00").put("b1", "DA").put("b2", "no").put("b3", "2025-05-01")
                .put("n1", "73").put("n2", "12,50").put("bad", "x7");
        Map<String, String> mapping = new java.util.HashMap<>();
        raw.fieldNames().forEachRemaining(f -> mapping.put(f, f));
        ImportRow row = new ImportRow(raw, mapping, null);
        LocalDate expected = LocalDate.of(2026, 10, 28);
        assertThat(row.date("d1")).isEqualTo(expected);
        assertThat(row.date("d2")).isEqualTo(expected);
        assertThat(row.date("d3")).isEqualTo(expected);
        assertThat(row.date("d4")).isEqualTo(expected);
        assertThat(row.date("d5")).isEqualTo(expected);
        assertThat(row.bool("b1")).isTrue();
        assertThat(row.bool("b2")).isFalse();
        assertThat(row.bool("b3")).isTrue();
        assertThat(row.integer("n1")).isEqualTo(73);
        assertThat(row.decimal("n2")).isEqualByComparingTo("12.50");
        assertThat(row.errors()).isEmpty();
        row.integer("bad");
        assertThat(row.errors()).hasSize(1);
    }

    @Test
    void mappingByAliasAccentsAndExplicitOverride() {
        Map<String, List<String>> fields = Map.of(
                "member_number", List.of("card_number", "broj_kartice"),
                "last_name", List.of("surname", "prezime"),
                "email", List.of("e_mail"));
        List<String> headers = List.of("Broj kartice", "Prézime", "E-mail", "Other");
        Map<String, String> m = LoyaltyImportWorker.resolveMapping(fields, headers, null);
        assertThat(m).containsEntry("member_number", "Broj kartice").containsEntry("last_name", "Prézime").containsEntry("email", "E-mail");

        Map<String, String> explicit = LoyaltyImportWorker.resolveMapping(fields, headers, om.createObjectNode().put("email", "Other"));
        assertThat(explicit).containsEntry("email", "Other");
    }

    @Test
    void duplicateAndBlankHeadersGetUniqueNames() {
        assertThat(LoyaltyImportWorker.uniqueHeaders(List.of("Name", "Name", " "))).containsExactly("Name", "Name_2", "column_3");
    }

    @Test
    void operaStatusesMap() {
        var map = StayImportApplier.DEFAULT_STATUS;
        assertThat(StayImportApplier.mapStatus("Checked Out", map)).isEqualTo(PmsReservation.Status.CHECKED_OUT);
        assertThat(StayImportApplier.mapStatus("NO SHOW", map)).isEqualTo(PmsReservation.Status.NO_SHOW);
        assertThat(StayImportApplier.mapStatus("due in", map)).isEqualTo(PmsReservation.Status.RESERVED);
        assertThat(StayImportApplier.mapStatus("IN-HOUSE", map)).isEqualTo(PmsReservation.Status.IN_HOUSE);
        assertThat(StayImportApplier.mapStatus("CANCELED", map)).isEqualTo(PmsReservation.Status.CANCELLED);
        assertThat(StayImportApplier.mapStatus("WHATEVER", map)).isNull();
    }
}
