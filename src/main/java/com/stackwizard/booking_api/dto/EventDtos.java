package com.stackwizard.booking_api.dto;

import com.stackwizard.booking_api.model.Event;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class EventDtos {
    private EventDtos() {
    }

    @Data
    public static class StatusChangeRequest {
        private Event.Status status;
        private Long outcomeReasonId;
        private String note;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApplyPackageRequest {
        private Long packageProductId;
        private Long resourceId;
        private LocalDate date;
        private LocalDateTime startsAt;
        private Integer pax;
    }

    @Data
    @Builder
    public static class Financials {
        private String currency;
        private BigDecimal rentTotal;
        private BigDecimal itemsTotal;
        private BigDecimal total;
        private BigDecimal costTotal;
        private BigDecimal margin;
        private Integer billablePax;
        private List<FunctionFinancials> functions;
    }

    @Data
    @Builder
    public static class FunctionFinancials {
        private Long functionId;
        private String name;
        private String functionType;
        private Long resourceId;
        private String resourceName;
        private Long rentReservationId;
        private String rentStatus;
        private String rentUom;
        private Integer rentQty;
        private BigDecimal rentUnitPrice;
        private BigDecimal rentAmount;
        private BigDecimal itemsAmount;
        private BigDecimal costAmount;
    }

    @Data
    @Builder
    public static class SpaceGridRow {
        private Long resourceId;
        private String resourceName;
        private String resourceType;
        private List<SpaceGridBooking> bookings;
    }

    @Data
    @Builder
    public static class SpaceGridBooking {
        private String kind;
        private String status;
        private LocalDateTime startsAt;
        private LocalDateTime endsAt;
        private Long eventId;
        private String eventName;
        private String eventStatus;
        private Long functionId;
        private String functionName;
        private Long reservationRequestId;
    }
}
