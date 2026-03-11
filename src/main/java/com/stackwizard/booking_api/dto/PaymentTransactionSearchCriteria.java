package com.stackwizard.booking_api.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class PaymentTransactionSearchCriteria {
    private Long tenantId;
    private Long transactionId;

    private Long reservationRequestId;
    private Long reservation_request;
    private Long reservation_reqst;

    private Long paymentIntentId;
    private List<String> paymentTypes;
    private List<String> statuses;
    private List<String> currencies;

    private String externalRef;
    private String note;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime createdFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime createdTo;

    private BigDecimal amountMin;
    private BigDecimal amountMax;
}
