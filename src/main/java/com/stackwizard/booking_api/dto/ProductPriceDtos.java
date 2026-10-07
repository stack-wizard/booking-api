package com.stackwizard.booking_api.dto;

import com.stackwizard.booking_api.model.ReservationRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class ProductPriceDtos {
    private ProductPriceDtos() {
    }

    /** A price profile validity window that prices can be attached to. */
    public record Period(Long id,
                         Long priceProfileId,
                         String profileName,
                         String currency,
                         ReservationRequest.Type requestType,
                         LocalDate dateFrom,
                         LocalDate dateTo,
                         String description) {
    }

    public record PriceRow(Long id,
                           Period period,
                           String uom,
                           BigDecimal price,
                           LocalTime startTime,
                           LocalTime endTime) {
    }

    public record PriceInput(Long id, Long priceProfileDateId, String uom, BigDecimal price, LocalTime startTime, LocalTime endTime) {
    }

    public record SaveRequest(List<PriceInput> upserts, List<Long> deleteIds) {
    }

    /** {@code percent} = +10 raises by 10 %; {@code roundTo} e.g. 0.01, 0.5 or 1. Empty filters = all prices of the product. */
    public record AdjustRequest(BigDecimal percent, List<Long> priceProfileDateIds, String uom, BigDecimal roundTo) {
    }

    /** Copies every price of one period into another (e.g. next season), optionally changed by a percentage. */
    public record CopyRequest(Long fromPeriodId, Long toPeriodId, BigDecimal percent, BigDecimal roundTo) {
    }
}
