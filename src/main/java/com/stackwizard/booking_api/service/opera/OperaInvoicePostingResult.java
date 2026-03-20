package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.OperaPostingTarget;

public record OperaInvoicePostingResult(
        Invoice invoice,
        OperaPostingTarget postingTarget,
        String hotelCode,
        Long reservationId,
        Long cashierId,
        Integer folioWindowNo,
        JsonNode payload,
        JsonNode response
) {
}
