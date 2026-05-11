package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public interface OperaPostingClient {

    JsonNode postChargesAndPayments(OperaTenantConfigResolver.OperaResolvedConfig config,
                                    String hotelCode,
                                    String chainCode,
                                    Long reservationId,
                                    JsonNode payload);

    JsonNode postCharges(OperaTenantConfigResolver.OperaResolvedConfig config,
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

    JsonNode getReservations(OperaTenantConfigResolver.OperaResolvedConfig config,
                             String chainCode,
                             String hotelCode,
                             Map<String, List<String>> queryParams);
}
