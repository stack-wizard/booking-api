package com.stackwizard.booking_api.dto;

import com.stackwizard.booking_api.model.Season;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class SeasonDtos {
    private SeasonDtos() {
    }

    public record CalendarRow(Long id, String name, Long propertyTenantId, long seasonCount, List<String> usedByCatalogs) {
    }

    public record CalendarInput(String name, Long propertyTenantId) {
    }

    public record SeasonInput(String code, String name, LocalDate dateFrom, LocalDate dateTo, Season.Status status,
                              Integer displayOrder, String note) {
    }

    public record SeasonRow(Long id, Long seasonCalendarId, String code, String name, LocalDate dateFrom, LocalDate dateTo,
                            Season.Status status, Integer displayOrder, String note, List<Operating> operating,
                            long pricePeriodCount) {
    }

    public record Operating(Long locationNodeId, LocalTime openTime, LocalTime closeTime, Integer gridMinutes,
                            Integer minDurationMinutes, Integer maxDurationMinutes) {
    }

    public record CopyYearRequest(Integer fromYear, Integer toYear, Boolean shiftToSameWeekday, Boolean copyPrices,
                                  BigDecimal pricePercent, BigDecimal roundTo, Boolean dryRun) {
    }

    /** Counts of what was (or, with dryRun, would be) created; {@code planned} lists the new seasons. */
    public record CopyYearResult(boolean dryRun, int seasons, int operating, int pricePeriods, int prices,
                                 List<PlannedSeason> planned, List<String> skipped) {
    }

    public record PlannedSeason(String code, String name, LocalDate dateFrom, LocalDate dateTo, Long fromSeasonId) {
    }
}
