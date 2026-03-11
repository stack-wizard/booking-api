package com.stackwizard.booking_api.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class InvoiceSearchCriteria {
    private Long tenantId;
    private Long invoiceId;

    // Keep aliases to support existing frontend params.
    private Long reservationRequestId;
    private Long reservation_request;
    private Long reservation_reqst;

    private String invoiceNumber;
    private List<String> invoiceTypes;
    private List<String> statuses;
    private List<String> paymentStatuses;
    private List<String> fiscalizationStatuses;
    private List<String> currencies;

    private String customer;
    private String customerName;
    private String customerEmail;
    private String customerPhone;

    private String referenceTable;
    private Long referenceId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate invoiceDateFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate invoiceDateTo;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime createdFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime createdTo;

    private BigDecimal totalGrossMin;
    private BigDecimal totalGrossMax;

    private Boolean hasStorno;
}
