package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;

public interface OperaPostingClient {
    JsonNode postChargesAndPayments(OperaTenantConfigResolver.OperaResolvedConfig config,
                                    String hotelCode,
                                    String chainCode,
                                    Long reservationId,
                                    JsonNode payload);
}
