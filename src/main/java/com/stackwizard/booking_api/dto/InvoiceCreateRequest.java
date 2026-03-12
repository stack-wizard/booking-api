package com.stackwizard.booking_api.dto;

import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.model.IssuedByMode;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class InvoiceCreateRequest {
    private Long reservationRequestId;
    private Long requestId;

    private Long tenantId;
    private InvoiceType invoiceType;
    private LocalDate invoiceDate;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    private String currency;

    private String referenceTable;
    private Long referenceId;

    private IssuedByMode issuedByMode;
    private Long issuedByUserId;

    private List<InvoiceCreateItemRequest> items;
}
