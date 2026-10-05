package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.EventOrder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class EventDocumentServiceTest {

    @Test
    void beoWithSeparatorsAndAmpersandsRendersToPdf() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode snapshot = mapper.createObjectNode()
                .put("name", "Spring congress · R&D")
                .put("status", "DEFINITE")
                .put("account", "Adriatic Pharma <HR>")
                .put("dateFrom", "2027-04-14")
                .put("dateTo", "2027-04-15");
        ObjectNode function = snapshot.putArray("functions").addObject()
                .put("name", "Coffee break")
                .put("startsAt", "2027-04-14T10:30:00")
                .put("endsAt", "2027-04-14T11:00:00")
                .put("space", "Foyer")
                .put("pax", 120);
        function.putArray("items").addObject()
                .put("product", "Coffee break")
                .put("description", "vegan · gluten free")
                .put("qty", 240)
                .put("uom", "UNIT")
                .putArray("allergens").add("nuts");

        String html = EventDocumentService.renderOrderHtml(snapshot, 2, EventOrder.Status.ISSUED);
        byte[] pdf = EventDocumentService.renderPdf(html);

        assertThat(html).doesNotContain("&middot;").contains("R&amp;D").contains("&lt;HR&gt;");
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
