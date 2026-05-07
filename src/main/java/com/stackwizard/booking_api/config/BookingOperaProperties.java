package com.stackwizard.booking_api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "booking.opera")
@Data
public class BookingOperaProperties {

    private final CheckIn checkIn = new CheckIn();
    private final ReservationTemplate reservation = new ReservationTemplate();

    @Data
    public static class CheckIn {
        private boolean enabled = false;
    }

    @Data
    public static class ReservationTemplate {
        private String roomType = "PM";
        private String ratePlanCode = "RATETEST";
        private String marketCode = "PKG";
        private String sourceCode = "LNG";
        private String guaranteeCode = "NON";
        private String paymentMethodCode = "CA";
        private String roomTypeCharged = "PM";
        private String sourceOfSaleType = "WLK";
        private String sourceOfSaleCode = "WLK";
        /** OHIP create reservation: matches working Postman / property samples. */
        private boolean suppressRate = true;
        private boolean pseudoRoom = true;
        private boolean roomNumberLocked = true;
        private String customerLanguage = "E";
        private String computedReservationStatus = "DueIn";
        /** {@code reservationPaymentMethods[].folioView} for create reservation (OHIP often expects {@code "1"}). */
        private String paymentFolioView = "1";
    }
}
