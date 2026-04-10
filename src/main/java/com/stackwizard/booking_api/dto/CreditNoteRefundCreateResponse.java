package com.stackwizard.booking_api.dto;

import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditNoteRefundCreateResponse {
    private PaymentTransactionDto paymentTransaction;
    private InvoicePaymentAllocation allocation;
}
