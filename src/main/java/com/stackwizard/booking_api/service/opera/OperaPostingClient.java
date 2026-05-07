package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;

public interface OperaPostingClient {

    JsonNode postChargesAndPayments(OperaTenantConfigResolver.OperaResolvedConfig config,
                                    String hotelCode,
                                    String chainCode,
                                    Long reservationId,
                                    JsonNode payload);

    JsonNode postCreateReservation(OperaTenantConfigResolver.OperaResolvedConfig config,
                                   String chainCode,
                                   String hotelCode,
                                   JsonNode body);

    JsonNode postCheckIn(OperaTenantConfigResolver.OperaResolvedConfig config,
                         String chainCode,
                         String hotelCode,
                         Long reservationId,
                         JsonNode body);

    JsonNode postPayment(OperaTenantConfigResolver.OperaResolvedConfig config,
                         String chainCode,
                         String hotelCode,
                         Long reservationId,
                         JsonNode body);
}
