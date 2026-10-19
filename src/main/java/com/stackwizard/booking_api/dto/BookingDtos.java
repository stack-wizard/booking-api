package com.stackwizard.booking_api.dto;

import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingRoomBlock;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class BookingDtos {
    private BookingDtos() {
    }

    @Data
    public static class StatusChangeRequest {
        /** Chain status code; wins over {@link #status}. */
        private String statusCode;
        /** Phase; moves to the first allowed status of that phase. */
        private Booking.Status status;
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
        private BigDecimal roomsTotal;
        private List<FunctionFinancials> functions;
        /** Subtotal per hotel, i.e. per reservation request of the booking. */
        private List<HotelFinancials> hotels;
    }

    /** Kind is derived from the needs: EVENT, GROUP, CONFERENCE (functions and rooms), SPACE_RENTAL, EMPTY. */
    @Data
    @Builder
    public static class BookingSummary {
        private Long bookingId;
        private String kind;
        private int functionCount;
        private int roomBlockCount;
        private List<SummaryHotel> hotels;
    }

    @Data
    @Builder
    public static class SummaryHotel {
        private Long tenantId;
        private String hotelName;
    }

    @Data
    @Builder
    public static class HotelFinancials {
        private Long tenantId;
        private String hotelName;
        private Long reservationRequestId;
        private String requestStatus;
        private BigDecimal rentAmount;
        private BigDecimal itemsAmount;
        private BigDecimal roomsAmount;
        private BigDecimal total;
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
        private Long tenantId;
        private String hotelName;
        private Long resourceId;
        private String resourceName;
        private String resourceType;
        /** Pool size for POOL resources, null for EXACT. */
        private Integer totalUnits;
        private Long parentPoolId;
        private List<SpaceGridBooking> bookings;
    }

    @Data
    @Builder
    public static class SpaceGridBooking {
        private String kind;
        private String status;
        private LocalDateTime startsAt;
        private LocalDateTime endsAt;
        private Integer units;
        private Long bookingId;
        private String bookingName;
        private String bookingStatus;
        private Long functionId;
        private String functionName;
        private Long reservationRequestId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoomBlockRequest {
        /** Hotel of the block; taken from the room type when {@link #roomResourceId} is set. */
        private Long propertyTenantId;
        /** Room-type pool of the hotel; null keeps the block unplaced. */
        private Long roomResourceId;
        private String roomTypeLabel;
        private LocalDate dateFrom;
        /** Departure day. */
        private LocalDate dateTo;
        private Integer paxPerRoom;
        private Long ratePriceProfileId;
        private String notes;
        /** Quota for every night that {@link #nights} does not set. */
        private Integer rooms;
        private List<RoomNight> nights;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlaceRoomBlockRequest {
        private Long roomResourceId;
    }

    /**
     * Availability to pick a room type for a room-block need. {@code LOCAL} uses allocation;
     * {@code OPERA} calls OHIP INV hotelInventory (daily free by room type).
     */
    @Data
    @Builder
    public static class PlacementAvailability {
        private Long blockId;
        private Long propertyTenantId;
        private String hotelCode;
        /** LOCAL or OPERA. */
        private String source;
        /** NIGHT = free units per night (LOCAL allocation or OPERA INV daily). */
        private String granularity;
        private String warning;
        private LocalDate dateFrom;
        private LocalDate dateTo;
        private List<RoomNight> requested;
        private List<PlacementRoomType> roomTypes;
    }

    @Data
    @Builder
    public static class PlacementRoomType {
        private Long resourceId;
        private String code;
        private String name;
        private Integer capacity;
        /** Lowest free over the nights of the stay. */
        private Integer freeUnits;
        private boolean fits;
        private boolean placed;
        private List<PlacementNight> nights;
    }

    @Data
    @Builder
    public static class PlacementNight {
        private LocalDate night;
        private int requested;
        private Integer free;
        private Integer capacity;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoomNight {
        private LocalDate night;
        private Integer qty;
    }

    @Data
    @Builder
    public static class RoomBlockView {
        private BookingRoomBlock block;
        private String roomResourceName;
        private List<RoomNight> nights;
        private Integer roomNights;
        private BigDecimal amount;
        /** Line status per night (HOLD, CONFIRMED, CANCELLED); empty while the block is not placed. */
        private List<RoomLine> lines;
    }

    @Data
    @Builder
    public static class RoomLine {
        private Long reservationId;
        private LocalDate night;
        private Integer units;
        private String status;
        private BigDecimal amount;
    }

    @Data
    @Builder
    public static class FitResult {
        private Long bookingId;
        private List<FitHotel> hotels;
    }

    @Data
    @Builder
    public static class FitHotel {
        private Long tenantId;
        private String name;
        private String hotelCode;
        /** Every need has at least one available candidate in this hotel. */
        private boolean fitsAll;
        private int fittingNeeds;
        private List<FitNeed> needs;
    }

    @Data
    @Builder
    public static class FitNeed {
        /** FUNCTION or ROOM_BLOCK. */
        private String kind;
        private Long needId;
        private String label;
        private LocalDateTime startsAt;
        private LocalDateTime endsAt;
        private Integer pax;
        private String setupStyle;
        /** Highest nightly quota of a room block. */
        private Integer rooms;
        private Long placedTenantId;
        private Long placedResourceId;
        private boolean fits;
        private List<FitCandidate> candidates;
    }

    @Data
    @Builder
    public static class FitCandidate {
        private Long resourceId;
        private String name;
        private String resourceType;
        /** Seats in the requested setup (largest setup when none is requested); pool size for rooms. */
        private Integer capacity;
        private boolean available;
        /** Lowest number of free units over the need's nights (rooms and pools). */
        private Integer freeUnits;
        private boolean placed;
    }

    @Data
    @Builder
    public static class RoomGridRow {
        private Long tenantId;
        private String hotelName;
        private Long resourceId;
        private String resourceName;
        private String resourceType;
        private Integer totalUnits;
        private List<RoomGridNight> nights;
    }

    @Data
    @Builder
    public static class RoomGridNight {
        private LocalDate night;
        /** Peak units held that night (booking blocks and individual reservations). */
        private int booked;
        /** Part of {@link #booked} held by booking room blocks. */
        private int blocked;
        private int free;
    }
}
