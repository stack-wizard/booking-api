package com.stackwizard.booking_api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutReadinessDto {
    /**
     * True when there are no blocking invoice issues (client may still show Opera warnings).
     */
    private boolean ready;
    private List<String> blockers;
    private List<CheckoutInvoiceWarningDto> warnings;
}
