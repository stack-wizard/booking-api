package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

public final class CrmReportDtos {
    private CrmReportDtos() {
    }

    @Data
    @Builder
    public static class FunnelStageRow {
        private Long stageId;
        private String stageName;
        private Integer displayOrder;
        private long entered;
        private long advanced;
        private long stuck;
    }

    @Data
    @Builder
    public static class ConversionRow {
        private String dimension;
        private String key;
        private long won;
        private long lost;
        private long turnedDown;
        private long cancelled;
        private BigDecimal winRate;
    }

    @Data
    @Builder
    public static class StageDurationRow {
        private Long stageId;
        private String stageName;
        private Double avgHours;
        private Double medianHours;
        private long samples;
    }

    @Data
    @Builder
    public static class RevenueRow {
        private String dimension;
        private String key;
        private Long accountId;
        private long events;
        private BigDecimal revenue;
        private BigDecimal cost;
        private BigDecimal margin;
        private BigDecimal marginPercent;
    }

    @Data
    @Builder
    public static class RevenueResponse {
        private List<RevenueRow> byAccount;
        private List<RevenueRow> bySegment;
    }

    @Data
    @Builder
    public static class UtilisationRow {
        private Long resourceId;
        private String resourceName;
        private double bookedHours;
        private double availableHours;
        private BigDecimal utilisation;
    }

    @Data
    @Builder
    public static class UtilisationResponse {
        private int days;
        private List<UtilisationRow> spaces;
    }

    @Data
    @Builder
    public static class FunnelResponse {
        private List<FunnelStageRow> stages;
    }

    @Data
    @Builder
    public static class ConversionResponse {
        private List<ConversionRow> byOwner;
        private List<ConversionRow> bySegment;
        private List<ConversionRow> byPipeline;
    }

    @Data
    @Builder
    public static class StageDurationResponse {
        private List<StageDurationRow> stages;
    }
}
