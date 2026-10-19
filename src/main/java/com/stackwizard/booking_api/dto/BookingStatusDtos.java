package com.stackwizard.booking_api.dto;

import com.stackwizard.booking_api.model.Booking;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public final class BookingStatusDtos {
    private BookingStatusDtos() {
    }

    /** Chain status with the codes it may move to; an empty {@code next} follows the phase rules. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Status {
        private Long id;
        private String code;
        private String label;
        private Booking.Status phase;
        private String color;
        private Boolean start;
        private Integer displayOrder;
        private Boolean active;
        private String operaCode;
        private List<String> next;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperaImportResult {
        private int created;
        private int linked;
        private int transitions;
        private int skippedTransitions;
        private int removedTransitions;
        private int deactivated;
    }
}
