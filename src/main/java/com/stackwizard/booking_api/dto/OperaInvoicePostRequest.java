package com.stackwizard.booking_api.dto;

import lombok.Data;

@Data
public class OperaInvoicePostRequest {
    private String baseUrl;
    private String requestPath;
    private String appKey;
    private String accessToken;
    private String hotelCode;
    private Long reservationId;
    private Long cashierId;
    private Integer folioWindowNo;
    private String postingReference;
    private String postingRemark;
    private String comments;
    private String paymentAction;
    private Boolean applyRoutingInstructions;
    private Boolean autoPosting;
    private Boolean force;
}
