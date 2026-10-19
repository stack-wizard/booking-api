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

    /** GET on an OHIP path (e.g. {@code /blk/config/v1/blockStatusCodes}); query values must already be URL-encoded. */
    JsonNode get(OperaTenantConfigResolver.OperaResolvedConfig config,
                 String chainCode,
                 String hotelCode,
                 String path,
                 Map<String, List<String>> queryParams);

    /** POST on an OHIP path; returns the body and the Location header when OPERA sends one (e.g. postBlock). */
    WriteResult post(OperaTenantConfigResolver.OperaResolvedConfig config,
                     String chainCode,
                     String hotelCode,
                     String path,
                     JsonNode body);

    /** PUT on an OHIP path (e.g. allocationRange). */
    JsonNode put(OperaTenantConfigResolver.OperaResolvedConfig config,
                 String chainCode,
                 String hotelCode,
                 String path,
                 JsonNode body);

    /** DELETE on an OHIP path (e.g. an event). */
    JsonNode delete(OperaTenantConfigResolver.OperaResolvedConfig config,
                    String chainCode,
                    String hotelCode,
                    String path);

    record WriteResult(JsonNode body, String location) {
    }
}
