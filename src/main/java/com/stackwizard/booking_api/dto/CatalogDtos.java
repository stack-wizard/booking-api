package com.stackwizard.booking_api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.stackwizard.booking_api.model.CatalogItem;
import com.stackwizard.booking_api.model.PriceProfileDate;
import com.stackwizard.booking_api.model.ProductPackageListing;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.SalesCatalog;
import com.stackwizard.booking_api.model.Season;
import com.stackwizard.booking_api.service.PackageCatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Shapes of the sales catalog API: web ({@code /api/catalogs}) and admin ({@code /api/sales-catalogs}). */
public final class CatalogDtos {
    private CatalogDtos() {
    }

    // ------------------------------------------------------------------ web

    public record CatalogSummary(String code, String name, SalesCatalog.SaleMode saleMode) {
    }

    /** {@code eventTypes}: what the website lets the guest pick; empty means the catalog asks for none. */
    public record CatalogDetail(String code, String name, SalesCatalog.SaleMode saleMode, String currency,
                                List<EventTypeView> eventTypes) {
    }

    public record EventTypeView(String code, String label) {
    }

    public record SeasonView(String code, String name, LocalDate dateFrom, LocalDate dateTo, Season.Status status) {
    }

    public record Image(String url, boolean isDefault) {
    }

    public record Setup(String setupStyle, Integer capacity) {
    }

    /** Why the price is what it is (web view of the applied price rule). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rule(String season, PriceProfileDate.PeriodKind periodKind, String daysOfWeek) {
    }

    public record Price(String uom, BigDecimal amountGross, String currency, Rule rule) {
    }

    /**
     * One catalog item as the web sees it. For products and packages {@code resourceId}, {@code areaSqm} and
     * {@code setups} are null; package fields ({@code minPax} ... {@code components}) are null for other kinds.
     * {@code available} is null when no date was given.
     */
    public record ItemView(Long itemId,
                           CatalogItem.Kind kind,
                           String group,
                           Long resourceId,
                           Long productId,
                           String name,
                           String description,
                           List<Image> images,
                           BigDecimal areaSqm,
                           Boolean naturalLight,
                           List<Setup> setups,
                           List<Price> prices,
                           Boolean available,
                           Integer displayOrder,
                           Integer minPax,
                           Integer maxPax,
                           ProductPackageListing.Duration duration,
                           LocalTime defaultStartTime,
                           String setupStyle,
                           BigDecimal pricePerPerson,
                           List<PackageCatalogService.CatalogComponent> components) {
    }

    public record Position(Long resourceId, String polygon, String label, String status, boolean selectable) {
    }

    public record MapView(Long mapId, String name, String imageUrl, String svgOverlay, List<Position> positions) {
    }

    public enum DayFormat { FULL_DAY, HALF_DAY }

    public record QuoteItem(@NotNull @Positive Long itemId,
                            @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal quantity) {
    }

    public record QuoteRequest(@NotNull LocalDate dateFrom,
                               LocalDate dateTo,
                               @Min(1) @Max(10000) Integer attendees,
                               DayFormat dayFormat,
                               @NotEmpty @Size(max = 100) List<@NotNull @Valid QuoteItem> items) {
        @AssertTrue(message = "dateTo must not be before dateFrom")
        public boolean isDateRangeValid() {
            return dateFrom == null || dateTo == null || !dateTo.isBefore(dateFrom);
        }
    }

    public record QuoteLine(Long itemId,
                            String name,
                            LocalDate date,
                            int days,
                            BigDecimal quantity,
                            String uom,
                            BigDecimal unitPriceGross,
                            BigDecimal totalGross,
                            BigDecimal taxPercent,
                            Rule rule) {
    }

    public record QuoteResponse(String currency,
                                List<QuoteLine> lines,
                                BigDecimal totalGross,
                                BigDecimal totalNet,
                                boolean hasItemsOnRequest) {
    }

    // ------------------------------------------------------------------ admin

    public record CatalogInput(String code, String name, SalesCatalog.SaleMode saleMode, Long seasonCalendarId,
                               ReservationRequest.Type requestType, Long propertyTenantId, Boolean active, String currency,
                               List<String> eventTypes) {
    }

    /** Admin row of a catalog item with the underlying resource / product, image and today's price. */
    public record AdminItem(@JsonUnwrapped CatalogItem item,
                            String sourceName,
                            String displayName,
                            String imageUrl,
                            List<Price> pricesToday,
                            List<Long> seasonIds) {
    }

    public record BulkRequest(Long resourceMapId, List<Long> resourceIds, List<Long> productIds) {
    }

    public record BulkResult(int added, int skipped, List<CatalogItem> items) {
    }

    public record MapLink(Long resourceMapId, Long seasonId) {
    }

    public record Preview(LocalDate date, SeasonView season, boolean sellable, String reason,
                          List<ItemView> items, List<MapView> maps) {
    }

    public record ProductCatalogItem(Long catalogId, String catalogCode, String catalogName, CatalogItem item) {
    }
}
