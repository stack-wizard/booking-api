package com.stackwizard.booking_api.dto;

import com.stackwizard.booking_api.model.IssuedByMode;
import lombok.Data;

@Data
public class InvoiceIssueRequest {
    private IssuedByMode issuedByMode;
    private Long issuedByUserId;
    private Long businessPremiseId;
    private Long cashRegisterId;
}
