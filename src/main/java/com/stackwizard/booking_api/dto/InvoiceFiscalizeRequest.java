package com.stackwizard.booking_api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.model.IssuedByMode;
import lombok.Data;

@Data
public class InvoiceFiscalizeRequest {
    private JsonNode ofisPayload;
    private JsonNode additionalInfo;
    private JsonNode userDefinedFields;
    private JsonNode hotelInfo;
    private JsonNode reservationInfo;
    private JsonNode fiscalFolioUserInfo;
    private JsonNode collectingAgentPropertyInfo;
    private JsonNode versionInfo;
    private JsonNode fiscalPartnerResponse;

    private IssuedByMode issuedByMode;
    private Long issuedByUserId;
    private Long businessPremiseId;
    private Long cashRegisterId;

    private String hotelCode;
    private String terminalId;
    private String terminalAddessAndPort;
    private String propertyTaxNumber;
    private String countryCode;
    private String countryName;
    private String application;
    private String command;
    private String documentType;
    private String fiscalTimeoutPeriod;
    private String operaFiscalBillNo;
    private String window;
    private String cashierNumber;
    private String cashierId;
    private String fiscalFolioStatus;
    private String appUser;
    private String appUserId;
    private String employeeNumber;
    private String reservationOperaId;
    private String confirmationNo;

    private String defaultChargeTrxCode;
    private String tax1TrxCode;
    private String tax2TrxCode;
    private String cashPaymentTrxCode;
    private String cardPaymentTrxCode;
    private String bankPaymentTrxCode;
    private String roomChargePaymentTrxCode;
}
