package com.stackwizard.booking_api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutInvoiceWarningDto {
    private Long invoiceId;
    private String invoiceNumber;
    private String invoiceType;
    private String operaPostingStatus;
    private String message;
}
