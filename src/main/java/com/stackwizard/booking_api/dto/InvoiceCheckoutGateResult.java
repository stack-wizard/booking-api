package com.stackwizard.booking_api.dto;

import java.util.List;

public record InvoiceCheckoutGateResult(List<String> blockers, List<CheckoutInvoiceWarningDto> warnings) {
    public boolean hasBlockers() {
        return blockers != null && !blockers.isEmpty();
    }
}
