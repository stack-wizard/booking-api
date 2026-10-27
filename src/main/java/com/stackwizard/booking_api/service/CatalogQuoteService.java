package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.CatalogDtos;
import com.stackwizard.booking_api.model.CatalogItem;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.SalesCatalog;
import com.stackwizard.booking_api.model.Season;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Indicative price of a web selection, per day. Consecutive days with the same price and rule merge into one line.
 * <ul>
 *   <li>per-day UOMs (PAX, DAY, HALF_DAY, HOUR, NIGHT): one price per day; quantity is per day and defaults to
 *       attendees (PAX), 8 / 4 hours (HOUR, full / half day) or 1</li>
 *   <li>other UOMs (UNIT, ...): one line on the first day, quantity defaults to 1</li>
 *   <li>resources (halls) use UOM HALF_DAY for a half-day format when the product offers it, otherwise DAY</li>
 *   <li>packages are priced per person per day ({@code PackageService.quote}); quantity defaults to attendees</li>
 *   <li>no price: {@code unitPriceGross = null}, {@code hasItemsOnRequest = true}</li>
 * </ul>
 */
@Service
public class CatalogQuoteService {
    static final Set<String> PER_DAY_UOMS = Set.of("PAX", "PERSON", "DAY", "HALF_DAY", "HALFDAY", "HOUR", "NIGHT");
    static final Set<String> PER_PERSON_UOMS = Set.of("PAX", "PERSON");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CatalogViewService viewService;
    private final PriceListEntryResolver resolver;

    public CatalogQuoteService(CatalogViewService viewService, PriceListEntryResolver resolver) {
        this.viewService = viewService;
        this.resolver = resolver;
    }

    @Transactional(readOnly = true)
    public CatalogDtos.QuoteResponse quote(SalesCatalog catalog, CatalogDtos.QuoteRequest request) {
        if (request == null || request.dateFrom() == null) {
            throw new IllegalArgumentException("dateFrom is required");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        List<LocalDate> days = CatalogViewService.days(request.dateFrom(), request.dateTo());
        Map<LocalDate, Season> seasons = new HashMap<>();
        for (LocalDate d : days) {
            CatalogViewService.DayCheck check = viewService.day(catalog, d);
            if (!check.sellable()) {
                throw new IllegalArgumentException(check.reason());
            }
            seasons.put(d, check.season());
        }
        CatalogViewService.Offer offer = viewService.offer(catalog);
        Map<Long, CatalogItem> byId = new HashMap<>();
        offer.items().forEach(i -> byId.put(i.getId(), i));
        CatalogDtos.DayFormat format = request.dayFormat() != null ? request.dayFormat() : CatalogDtos.DayFormat.FULL_DAY;

        List<CatalogDtos.QuoteLine> lines = new ArrayList<>();
        for (int idx = 0; idx < request.items().size(); idx++) {
            CatalogDtos.QuoteItem qi = request.items().get(idx);
            String at = "items[" + idx + "]";
            if (qi == null || qi.itemId() == null) {
                throw new IllegalArgumentException(at + ".itemId is required");
            }
            CatalogItem item = byId.get(qi.itemId());
            if (item == null) {
                throw new IllegalArgumentException(at + ": item " + qi.itemId() + " is not in catalog " + catalog.getCode());
            }
            String name = name(item, offer);
            if (!Boolean.TRUE.equals(item.getPublished())) {
                throw new IllegalArgumentException(at + ": " + name + " is not published");
            }
            if (!offer.visible(item)) {
                throw new IllegalArgumentException(at + ": " + name + " is not offered in this hotel");
            }
            for (LocalDate d : days) {
                if (!CatalogViewService.offeredOn(item, d, seasons.get(d), offer.seasonIds(item))) {
                    throw new IllegalArgumentException(at + ": " + name + " is not offered on " + d);
                }
            }
            lines.addAll(itemLines(catalog, offer, item, name, qi.quantity(), request.attendees(), format, days, at));
        }
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        boolean onRequest = false;
        for (CatalogDtos.QuoteLine line : lines) {
            if (line.totalGross() == null) {
                onRequest = true;
                continue;
            }
            totalGross = totalGross.add(line.totalGross());
            BigDecimal tax = line.taxPercent() != null ? line.taxPercent() : BigDecimal.ZERO;
            totalNet = totalNet.add(line.totalGross().multiply(HUNDRED).divide(HUNDRED.add(tax), 8, RoundingMode.HALF_UP));
        }
        return new CatalogDtos.QuoteResponse(catalog.getCurrency(), lines, money(totalGross), money(totalNet), onRequest);
    }

    private List<CatalogDtos.QuoteLine> itemLines(SalesCatalog catalog, CatalogViewService.Offer offer, CatalogItem item,
                                                  String name, BigDecimal quantity, Integer attendees,
                                                  CatalogDtos.DayFormat format, List<LocalDate> days, String at) {
        Product product = offer.product(item);
        boolean isPackage = item.getItemKind() == CatalogItem.Kind.PACKAGE;
        if (isPackage) {
            if (attendees == null && quantity == null) {
                throw new IllegalArgumentException(at + ": attendees is required to price package " + name);
            }
            int pax = attendees != null ? attendees : quantity.intValue();
            if (item.getMinPax() != null && pax < item.getMinPax() || item.getMaxPax() != null && pax > item.getMaxPax()) {
                throw new IllegalArgumentException(at + ": " + name + " is for " + paxRange(item) + " persons");
            }
        }
        String uom = isPackage ? "PAX" : product == null ? null : uom(item, product, format);
        boolean perDay = isPackage || uom != null && PER_DAY_UOMS.contains(uom);
        BigDecimal qty = quantity != null ? quantity : defaultQuantity(uom, attendees, format, name, at);
        BigDecimal tax = product == null ? null : plain(nz(product.getTax1Percent()).add(nz(product.getTax2Percent())));

        List<LocalDate> priced = perDay ? days : List.of(days.get(0));
        List<CatalogDtos.QuoteLine> out = new ArrayList<>();
        LocalDate runStart = null;
        int runDays = 0;
        BigDecimal runPrice = null;
        CatalogDtos.Rule runRule = null;
        for (LocalDate d : priced) {
            BigDecimal unit = null;
            CatalogDtos.Rule rule = null;
            if (isPackage) {
                unit = viewService.packagePricePerPerson(catalog, item, offer, d, attendees != null ? attendees : qty.intValue());
            } else if (product != null) {
                var resolved = resolver.resolve(product.getId(), uom, catalog.getCurrency(), offer.priceTenant(item), d, null,
                        catalog.getRequestType());
                if (resolved.isPresent()) {
                    unit = resolved.get().price();
                    rule = CatalogViewService.webRule(resolved.get().rule());
                }
            }
            if (runStart != null && Objects.equals(unit, runPrice) && Objects.equals(rule, runRule)
                    && d.equals(runStart.plusDays(runDays))) {
                runDays++;
                continue;
            }
            if (runStart != null) {
                out.add(line(item, name, runStart, runDays, qty, uom, runPrice, tax, runRule));
            }
            runStart = d;
            runDays = 1;
            runPrice = unit;
            runRule = rule;
        }
        if (runStart != null) {
            out.add(line(item, name, runStart, runDays, qty, uom, runPrice, tax, runRule));
        }
        return out;
    }

    private static CatalogDtos.QuoteLine line(CatalogItem item, String name, LocalDate date, int days, BigDecimal qty,
                                              String uom, BigDecimal unit, BigDecimal tax, CatalogDtos.Rule rule) {
        BigDecimal total = unit == null ? null : money(unit.multiply(qty).multiply(BigDecimal.valueOf(days)));
        return new CatalogDtos.QuoteLine(item.getId(), name, date, days, plain(qty), uom, unit, total, tax, rule);
    }

    /** Halls: HALF_DAY for a half-day format when offered, otherwise DAY when offered; else the default UOM. */
    static String uom(CatalogItem item, Product product, CatalogDtos.DayFormat format) {
        List<String> offered = CatalogViewService.uoms(product);
        String fallback = offered.isEmpty() ? null : offered.get(0);
        if (item.getItemKind() != CatalogItem.Kind.RESOURCE) {
            return fallback;
        }
        if (format == CatalogDtos.DayFormat.HALF_DAY) {
            for (String u : List.of("HALF_DAY", "HALFDAY")) {
                if (offered.contains(u)) {
                    return u;
                }
            }
        }
        return offered.contains("DAY") ? "DAY" : fallback;
    }

    static BigDecimal defaultQuantity(String uom, Integer attendees, CatalogDtos.DayFormat format, String name, String at) {
        if (uom != null && PER_PERSON_UOMS.contains(uom)) {
            if (attendees == null) {
                throw new IllegalArgumentException(at + ": attendees (or quantity) is required to price " + name + " per person");
            }
            return BigDecimal.valueOf(attendees);
        }
        if ("HOUR".equals(uom)) {
            return BigDecimal.valueOf(format == CatalogDtos.DayFormat.HALF_DAY ? 4 : 8);
        }
        return BigDecimal.ONE;
    }

    static String name(CatalogItem item, CatalogViewService.Offer offer) {
        if (item.getPublicName() != null) {
            return item.getPublicName();
        }
        Resource r = offer.resource(item);
        if (r != null) {
            return r.getName();
        }
        Product p = offer.product(item);
        return p != null ? p.getName() : "item " + item.getId();
    }

    private static String paxRange(CatalogItem item) {
        if (item.getMinPax() != null && item.getMaxPax() != null) {
            return item.getMinPax() + "-" + item.getMaxPax();
        }
        return item.getMinPax() != null ? "at least " + item.getMinPax() : "at most " + item.getMaxPax();
    }

    /** 25.0000 → 25, 60 → 60 (never 6E+1 in JSON). */
    static BigDecimal plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
