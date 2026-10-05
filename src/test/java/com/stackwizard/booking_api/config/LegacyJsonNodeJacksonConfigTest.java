package com.stackwizard.booking_api.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.model.CrmLead;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyJsonNodeJacksonConfigTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new LegacyJsonNodeJacksonConfig().legacyJsonNodeModule())
            .build();

    @Test
    void readsAndWritesJackson2TreesOnEntities() {
        CrmLead lead = mapper.readValue("""
                {"companyName":"Acme","attrs":{"inquiryType":"EVENT_CATERING","event":{"pax":40,"catering":["LUNCH"]}}}
                """, CrmLead.class);

        JsonNode attrs = lead.getAttrs();
        assertThat(attrs.path("event").path("pax").asInt()).isEqualTo(40);
        assertThat(attrs.path("event").path("catering").get(0).asText()).isEqualTo("LUNCH");

        String json = mapper.writeValueAsString(lead);
        assertThat(json).contains("\"attrs\":{\"inquiryType\":\"EVENT_CATERING\",\"event\":{\"pax\":40,\"catering\":[\"LUNCH\"]}}");
    }

    @Test
    void keepsNullAttrs() {
        CrmLead lead = mapper.readValue("{\"companyName\":\"Acme\",\"attrs\":null}", CrmLead.class);
        assertThat(lead.getAttrs()).isNull();
    }
}
