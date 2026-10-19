package com.stackwizard.booking_api.dto;

import com.stackwizard.booking_api.model.BookingOperaBlock;
import com.stackwizard.booking_api.model.BookingOperaPosting;
import com.stackwizard.booking_api.model.OperaCode;
import com.stackwizard.booking_api.model.OperaCodeMap;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.service.opera.OperaRoomTypeSyncService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class OperaDtos {
    private OperaDtos() {
    }

    /** Outcome of importing one kind of codes; {@code error} is set when OPERA refused the request. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportKindResult {
        private OperaCode.Kind kind;
        private int imported;
        private int deactivated;
        private String error;
    }

    /** An empty {@code operaCode} removes the mapping. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MapRequest {
        private OperaCodeMap.Kind kind;
        private String localRef;
        private String operaCode;
    }

    /** A catering item or space rental whose amount differs from what was posted to the block's Posting Master. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PendingCharge {
        private Long blockRowId;
        private String hotelCode;
        private Long functionId;
        private Long itemId;
        private BookingOperaPosting.Source source;
        private String description;
        private Long productId;
        private String trxCode;
        private BigDecimal amount;
        private String problem;
    }

    /** OPERA side of a booking: one block per hotel, what was posted to the PMs and what is still to post. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BookingOpera {
        private List<BookingOperaBlockView> blocks;
        private List<BookingOperaPosting> postings;
        private List<PendingCharge> pending;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BookingOperaBlockView {
        private Long id;
        private Long propertyTenantId;
        private String hotelCode;
        private Long requestId;
        private String blockId;
        private String blockCode;
        private String statusCode;
        private String statusLabel;
        private boolean allowsPickup;
        private String pmReservationId;
        private BookingOperaBlock.SyncStatus syncStatus;
        private String lastError;
        private OffsetDateTime syncedAt;
        private LocalDate dateFrom;
        private LocalDate dateTo;
    }

    /** Outcome of Setup from OPERA for one hotel. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SetupResult {
        private List<ImportKindResult> codes;
        private OperaRoomTypeSyncService.SyncResult roomTypes;
        private int spacesCreated;
        private int spacesUpdated;
        private int eventTypes;
        private int setupStyles;
        /** EVENT_TYPE + SETUP_STYLE + RATE_CODE map rows written or refreshed. */
        private int mapsUpserted;
        private BookingStatusDtos.OperaImportResult statuses;
        private List<String> warnings;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModesRequest {
        private OperaHotel.Mode spaceInventory;
        private OperaHotel.Mode roomInventory;
        private OperaHotel.Mode billing;
        private String defaultMarketCode;
        private String defaultSourceCode;
        private String defaultNonDeductReservationType;
        private String defaultDeductReservationType;
        /** Function-space rental code for events (CUSTOM when the amount is ours). */
        private String defaultRentalCode;
        private String defaultOriginCode;
        private String defaultBlockOwnerCode;
    }
}
