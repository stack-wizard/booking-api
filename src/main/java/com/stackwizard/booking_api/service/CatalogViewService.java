package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.AvailabilityResourceDto;
import com.stackwizard.booking_api.dto.AvailabilityResponse;
import com.stackwizard.booking_api.dto.CatalogDtos;
import com.stackwizard.booking_api.model.BookingCalendar;
import com.stackwizard.booking_api.model.CatalogItem;
import com.stackwizard.booking_api.model.CatalogItemSeason;
import com.stackwizard.booking_api.model.CatalogMap;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ProductImage;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceMap;
import com.stackwizard.booking_api.model.ResourceMapResource;
import com.stackwizard.booking_api.model.ResourceSetupCapacity;
import com.stackwizard.booking_api.model.SalesCatalog;
import com.stackwizard.booking_api.model.Season;
import com.stackwizard.booking_api.repository.CatalogItemRepository;
import com.stackwizard.booking_api.repository.CatalogItemSeasonRepository;
import com.stackwizard.booking_api.repository.CatalogMapRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ResourceMapRepository;
import com.stackwizard.booking_api.repository.ResourceMapResourceRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.repository.ResourceSetupCapacityRepository;
import com.stackwizard.booking_api.repository.SalesCatalogRepository;
import com.stackwizard.booking_api.repository.SeasonRepository;
import com.stackwizard.booking_api.security.TenantContext;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * How a sales catalog resolves for a date: season (sellable or not), offer (published, valid, in season, pax, hotel),
 * maps, availability and prices. Shared by the web API ({@code /api/catalogs}) and the admin preview.
 */
@Service
public class CatalogViewService {
    static final int MAX_RANGE_DAYS = 31;
    static final String STATUS_INACTIVE = "INACTIVE";
    static final String STATUS_AVAILABLE = "AVAILABLE";
    static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    private final SalesCatalogRepository catalogRepo;
    private final CatalogItemRepository itemRepo;
    private final CatalogItemSeasonRepository itemSeasonRepo;
    private final CatalogMapRepository catalogMapRepo;
    private final SeasonRepository seasonRepo;
    private final ResourceRepository resourceRepo;
    private final ProductRepository productRepo;
    private final ResourceMapRepository mapRepo;
    private final ResourceMapResourceRepository mapResourceRepo;
    private final ResourceSetupCapacityRepository capacityRepo;
    private final PriceListEntryResolver resolver;
    private final PackageService packageService;
    private final PackageCatalogService packageCatalogService;
    private final ProductVisibilityService visibility;
    private final ResourceSetupCapacityService spaceService;
    private final AvailabilityService availabilityService;
    private final ServiceCalendarService calendarService;

    public CatalogViewService(SalesCatalogRepository catalogRepo,
                              CatalogItemRepository itemRepo,
                              CatalogItemSeasonRepository itemSeasonRepo,
                              CatalogMapRepository catalogMapRepo,
                              SeasonRepository seasonRepo,
                              ResourceRepository resourceRepo,
                              ProductRepository productRepo,
                              ResourceMapRepository mapRepo,
                              ResourceMapResourceRepository mapResourceRepo,
                              ResourceSetupCapacityRepository capacityRepo,
                              PriceListEntryResolver resolver,
                              PackageService packageService,
                              PackageCatalogService packageCatalogService,
                              ProductVisibilityService visibility,
                              ResourceSetupCapacityService spaceService,
                              AvailabilityService availabilityService,
                              ServiceCalendarService calendarService) {
        this.catalogRepo = catalogRepo;
        this.itemRepo = itemRepo;
        this.itemSeasonRepo = itemSeasonRepo;
        this.catalogMapRepo = catalogMapRepo;
        this.seasonRepo = seasonRepo;
        this.resourceRepo = resourceRepo;
        this.productRepo = productRepo;
        this.mapRepo = mapRepo;
        this.mapResourceRepo = mapResourceRepo;
        this.capacityRepo = capacityRepo;
        this.resolver = resolver;
        this.packageService = packageService;
        this.packageCatalogService = packageCatalogService;
        this.visibility = visibility;
        this.spaceService = spaceService;
        this.availabilityService = availabilityService;
        this.calendarService = calendarService;
    }

    // ------------------------------------------------------------------ catalog lookup

    /** Active catalogs of the chain that the caller's hotel (X-Property-Id) can see. */
    @Transactional(readOnly = true)
    public List<SalesCatalog> activeCatalogs() {
        Long org = TenantResolver.requireOrgTenantId();
        Long hotel = TenantResolver.selectedHotelId();
        return catalogRepo.findByTenantIdOrderByCodeAscIdAsc(org).stream()
                .filter(c -> Boolean.TRUE.equals(c.getActive()))
                .filter(c -> hotel == null || c.getPropertyTenantId() == null || hotel.equals(c.getPropertyTenantId()))
                .toList();
    }

    /**
     * Catalog by code for the web: the selected hotel's own catalog first, then the chain catalog; without a hotel the
     * chain catalog, or the only hotel catalog with that code. 404 when inactive or unknown.
     */
    @Transactional(readOnly = true)
    public SalesCatalog requireActiveByCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("catalog code is required");
        }
        Long org = TenantResolver.requireOrgTenantId();
        Long property = TenantContext.getPropertyTenantId();
        List<SalesCatalog> candidates = catalogRepo.findByTenantIdAndCodeIgnoreCase(org, code.trim()).stream()
                .filter(c -> Boolean.TRUE.equals(c.getActive()))
                .toList();
        Optional<SalesCatalog> own = candidates.stream()
                .filter(c -> property != null && property.equals(c.getPropertyTenantId())).findFirst();
        if (own.isPresent()) {
            return own.get();
        }
        Optional<SalesCatalog> chain = candidates.stream().filter(c -> c.getPropertyTenantId() == null).findFirst();
        if (chain.isPresent()) {
            return chain.get();
        }
        Long hotel = TenantResolver.selectedHotelId();
        if (hotel == null && candidates.size() == 1) {
            return candidates.get(0);
        }
        if (hotel == null && candidates.size() > 1) {
            throw new IllegalArgumentException("Catalog " + code + " exists in several hotels; send X-Property-Id");
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalog not found: " + code);
    }

    // ------------------------------------------------------------------ seasons

    public record DayCheck(boolean sellable, Season season, String reason) {
    }

    /** A date is sellable when the catalog has no season calendar, or the date is in an OPEN season of it. */
    @Transactional(readOnly = true)
    public DayCheck day(SalesCatalog catalog, LocalDate date) {
        if (catalog.getSeasonCalendarId() == null) {
            return new DayCheck(true, null, null);
        }
        Optional<Season> season = seasonRepo.findOnDate(catalog.getSeasonCalendarId(), date);
        if (season.isEmpty()) {
            return new DayCheck(false, null, date + " is outside every season of catalog " + catalog.getCode());
        }
        if (season.get().getStatus() == Season.Status.CLOSED) {
            return new DayCheck(false, season.get(), date + " is in closed season '" + season.get().getName() + "'");
        }
        return new DayCheck(true, season.get(), null);
    }

    @Transactional(readOnly = true)
    public List<CatalogDtos.SeasonView> seasons(SalesCatalog catalog, LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from");
        }
        if (catalog.getSeasonCalendarId() == null) {
            return List.of();
        }
        return seasonRepo.findBySeasonCalendarIdOrderByDateFromAscIdAsc(catalog.getSeasonCalendarId()).stream()
                .filter(s -> from == null || !s.getDateTo().isBefore(from))
                .filter(s -> to == null || !s.getDateFrom().isAfter(to))
                .map(CatalogViewService::toView)
                .toList();
    }

    static CatalogDtos.SeasonView toView(Season s) {
        return s == null ? null : new CatalogDtos.SeasonView(s.getCode(), s.getName(), s.getDateFrom(), s.getDateTo(), s.getStatus());
    }

    // ------------------------------------------------------------------ items

    /**
     * Items of the catalog. With {@code date} (and optional {@code dateTo}) only items offered on every day of the
     * range; nothing when a day is not sellable. {@code groups} filters by item group (case-insensitive).
     */
    @Transactional(readOnly = true)
    public List<CatalogDtos.ItemView> items(SalesCatalog catalog, LocalDate date, LocalDate dateTo, Integer pax, Set<String> groups) {
        List<LocalDate> days = days(date, dateTo);
        if (pax != null && pax <= 0) {
            throw new IllegalArgumentException("pax must be > 0");
        }
        Map<LocalDate, Season> seasonByDay = new HashMap<>();
        for (LocalDate d : days) {
            DayCheck check = day(catalog, d);
            if (!check.sellable()) {
                return List.of();
            }
            seasonByDay.put(d, check.season());
        }
        Offer offer = offer(catalog);
        LocalDate priceDate = date != null ? date : LocalDate.now();
        Availability availability = new Availability(catalog, days);
        List<CatalogDtos.ItemView> out = new ArrayList<>();
        for (CatalogItem item : offer.items()) {
            if (!Boolean.TRUE.equals(item.getPublished())) {
                continue;
            }
            if (groups != null && !groups.isEmpty()
                    && (item.getItemGroup() == null || !groups.contains(item.getItemGroup().trim().toUpperCase(Locale.ROOT)))) {
                continue;
            }
            if (!offer.visible(item)) {
                continue;
            }
            boolean offered = days.stream().allMatch(d -> offeredOn(item, d, seasonByDay.get(d), offer.seasonIds(item)));
            if (!offered) {
                continue;
            }
            if (item.getItemKind() == CatalogItem.Kind.PACKAGE && pax != null
                    && (item.getMinPax() != null && pax < item.getMinPax() || item.getMaxPax() != null && pax > item.getMaxPax())) {
                continue;
            }
            out.add(view(catalog, item, offer, priceDate, pax, days.isEmpty() ? null : availability.of(item, offer, pax)));
        }
        return out;
    }

    /** Offered on the date: valid on it and (no season limit or the date's season is one of the item's). */
    static boolean offeredOn(CatalogItem item, LocalDate date, Season season, Set<Long> itemSeasons) {
        if (!item.validOn(date)) {
            return false;
        }
        if (itemSeasons == null || itemSeasons.isEmpty()) {
            return true;
        }
        return season != null && itemSeasons.contains(season.getId());
    }

    static List<LocalDate> days(LocalDate date, LocalDate dateTo) {
        if (date == null) {
            if (dateTo != null) {
                throw new IllegalArgumentException("dateTo needs date");
            }
            return List.of();
        }
        LocalDate end = dateTo != null ? dateTo : date;
        if (end.isBefore(date)) {
            throw new IllegalArgumentException("dateTo must not be before date");
        }
        long count = ChronoUnit.DAYS.between(date, end) + 1;
        if (count > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("A date range can span at most " + MAX_RANGE_DAYS + " days");
        }
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = date; !d.isAfter(end); d = d.plusDays(1)) {
            out.add(d);
        }
        return out;
    }

    /** Items of a catalog with their resources, products, seasons and the caller's hotel filter loaded once. */
    class Offer {
        private final SalesCatalog catalog;
        private final List<CatalogItem> items;
        private final Map<Long, Resource> resources;
        private final Map<Long, Product> products;
        private final Map<Long, Set<Long>> seasons;
        private final Long hotel;
        private final Map<Long, Boolean> productVisible = new HashMap<>();

        Offer(SalesCatalog catalog, List<CatalogItem> items) {
            this.catalog = catalog;
            this.items = items;
            this.resources = resourceRepo.findAllById(items.stream().map(CatalogItem::getResourceId).filter(Objects::nonNull).toList())
                    .stream().collect(Collectors.toMap(Resource::getId, Function.identity()));
            Set<Long> productIds = new HashSet<>();
            items.stream().map(CatalogItem::getProductId).filter(Objects::nonNull).forEach(productIds::add);
            this.products = new HashMap<>();
            productRepo.findAllById(productIds).forEach(p -> products.put(p.getId(), p));
            this.seasons = new HashMap<>();
            if (!items.isEmpty()) {
                for (CatalogItemSeason s : itemSeasonRepo.findByCatalogItemIdIn(items.stream().map(CatalogItem::getId).toList())) {
                    seasons.computeIfAbsent(s.getCatalogItemId(), k -> new HashSet<>()).add(s.getSeasonId());
                }
            }
            Long selected = TenantResolver.selectedHotelId();
            this.hotel = catalog.getPropertyTenantId() != null ? catalog.getPropertyTenantId() : selected;
        }

        List<CatalogItem> items() {
            return items;
        }

        Set<Long> seasonIds(CatalogItem item) {
            return seasons.getOrDefault(item.getId(), Set.of());
        }

        Resource resource(CatalogItem item) {
            return item.getResourceId() == null ? null : resources.get(item.getResourceId());
        }

        /** Product that carries the price: the item's product, or the resource's product. */
        Product product(CatalogItem item) {
            if (item.getProductId() != null) {
                return products.get(item.getProductId());
            }
            Resource r = resource(item);
            if (r == null) {
                return null;
            }
            if (r.getProduct() != null) {
                return r.getProduct();
            }
            return productRepo.findFirstByResource_IdOrderByIdAsc(r.getId()).orElse(null);
        }

        /** Hotel filter: resources of the hotel only; chain products only when the hotel offers them. */
        boolean visible(CatalogItem item) {
            if (hotel == null) {
                return true;
            }
            Resource r = resource(item);
            if (r != null) {
                return hotel.equals(r.getTenantId());
            }
            Product p = product(item);
            if (p == null) {
                return false;
            }
            if (!p.getTenantId().equals(catalog.getTenantId())) {
                return hotel.equals(p.getTenantId());
            }
            return productVisible.computeIfAbsent(p.getId(), id -> visibility.isVisible(p, hotel));
        }

        /** Tenant whose prices apply: the resource's hotel, else the catalog's / selected hotel, else the chain. */
        Long priceTenant(CatalogItem item) {
            Resource r = resource(item);
            if (r != null) {
                return r.getTenantId();
            }
            return hotel != null ? hotel : catalog.getTenantId();
        }
    }

    Offer offer(SalesCatalog catalog) {
        return new Offer(catalog, itemRepo.findForCatalog(catalog.getId()));
    }

    CatalogDtos.ItemView view(SalesCatalog catalog, CatalogItem item, Offer offer, LocalDate priceDate, Integer pax, Boolean available) {
        Resource r = offer.resource(item);
        Product p = offer.product(item);
        String name = item.getPublicName() != null ? item.getPublicName()
                : r != null ? r.getName() : p != null ? p.getName() : null;
        String description = item.getPublicDescription() != null ? item.getPublicDescription()
                : p != null ? p.getDescription() : null;
        List<CatalogDtos.Image> images = images(p);
        List<CatalogDtos.Setup> setups = null;
        if (r != null) {
            setups = capacityRepo.findByTenantIdAndResourceIdOrderBySetupStyleAsc(r.getTenantId(), r.getId()).stream()
                    .map(c -> new CatalogDtos.Setup(c.getSetupStyle(), c.getCapacity()))
                    .toList();
        }
        List<CatalogDtos.Price> prices = new ArrayList<>();
        BigDecimal pricePerPerson = null;
        List<PackageCatalogService.CatalogComponent> components = null;
        if (item.getItemKind() == CatalogItem.Kind.PACKAGE && p != null) {
            pricePerPerson = packagePricePerPerson(catalog, item, offer, priceDate, pax);
            if (pricePerPerson != null) {
                prices.add(new CatalogDtos.Price("PAX", pricePerPerson, catalog.getCurrency(), null));
            }
            components = packageCatalogService.catalogComponents(p.getTenantId(), p.getId());
        } else if (p != null) {
            for (String uom : uoms(p)) {
                resolver.resolve(p.getId(), uom, catalog.getCurrency(), offer.priceTenant(item), priceDate, null, catalog.getRequestType())
                        .ifPresent(rp -> prices.add(new CatalogDtos.Price(uom, rp.price(), catalog.getCurrency(), webRule(rp.rule()))));
            }
        }
        boolean isPackage = item.getItemKind() == CatalogItem.Kind.PACKAGE;
        return new CatalogDtos.ItemView(
                item.getId(),
                item.getItemKind(),
                item.getItemGroup(),
                item.getResourceId(),
                p != null ? p.getId() : item.getProductId(),
                name,
                description,
                images,
                r != null ? r.getAreaSqm() : null,
                r != null ? r.getNaturalLight() : null,
                setups,
                prices,
                available,
                item.getDisplayOrder(),
                isPackage ? item.getMinPax() : null,
                isPackage ? item.getMaxPax() : null,
                isPackage ? item.getDuration() : null,
                isPackage ? item.getDefaultStartTime() : null,
                isPackage || r != null ? item.getSetupStyle() : null,
                pricePerPerson,
                components);
    }

    BigDecimal packagePricePerPerson(SalesCatalog catalog, CatalogItem item, Offer offer, LocalDate date, Integer pax) {
        Product p = offer.product(item);
        int persons = pax != null ? pax : item.getMinPax() != null ? item.getMinPax() : 1;
        try {
            return packageService.quote(p.getTenantId(), offer.priceTenant(item), p.getId(), date, persons, catalog.getCurrency())
                    .pricePerPerson();
        } catch (IllegalArgumentException | IllegalStateException noPrice) {
            return null;
        }
    }

    static CatalogDtos.Rule webRule(PriceListEntryResolver.AppliedRule rule) {
        if (rule == null) {
            return null;
        }
        return new CatalogDtos.Rule(rule.season(), rule.periodKind(), rule.daysOfWeek());
    }

    static List<String> uoms(Product p) {
        Set<String> out = new LinkedHashSet<>();
        if (p.getDefaultUom() != null) {
            out.add(p.getDefaultUom().trim().toUpperCase(Locale.ROOT));
        }
        if (p.getExtraUoms() != null) {
            p.getExtraUoms().stream().map(u -> u.trim().toUpperCase(Locale.ROOT)).sorted().forEach(out::add);
        }
        return new ArrayList<>(out);
    }

    static List<CatalogDtos.Image> images(Product p) {
        if (p == null || p.getImages() == null) {
            return List.of();
        }
        return p.getImages().stream()
                .sorted(Comparator.comparing(ProductImage::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(i -> new CatalogDtos.Image(i.getImageUrl(), Boolean.TRUE.equals(i.getDefaultImage())))
                .toList();
    }

    /** Availability of items over a set of days, using the existing engines (per request cache). */
    final class Availability {
        private final SalesCatalog catalog;
        private final List<LocalDate> days;
        private final Map<String, Map<Long, String>> instantStatus = new HashMap<>();

        Availability(SalesCatalog catalog, List<LocalDate> days) {
            this.catalog = catalog;
            this.days = days;
        }

        Boolean of(CatalogItem item, Offer offer, Integer pax) {
            return switch (item.getItemKind()) {
                case PRODUCT -> Boolean.TRUE;
                case PACKAGE -> packageAvailable(item, pax);
                case RESOURCE -> {
                    Resource r = offer.resource(item);
                    yield r != null && resourceAvailable(r, pax);
                }
            };
        }

        /** Packages need a hotel to search spaces; without one (chain call) availability is unknown (null). */
        private Boolean packageAvailable(CatalogItem item, Integer pax) {
            if (pax == null) {
                return Boolean.TRUE;
            }
            if (TenantContext.getPropertyTenantId() == null) {
                return null;
            }
            try {
                for (LocalDate d : days) {
                    if (!packageCatalogService.availability(item.getProductId(), d, pax, null, catalog.getCurrency()).available()) {
                        return false;
                    }
                }
                return true;
            } catch (IllegalArgumentException | IllegalStateException e) {
                return false;
            }
        }

        boolean resourceAvailable(Resource r, Integer pax) {
            if (pax != null) {
                List<ResourceSetupCapacity> setups = capacityRepo.findByTenantIdAndResourceIdOrderBySetupStyleAsc(r.getTenantId(), r.getId());
                if (!setups.isEmpty() && setups.stream().map(ResourceSetupCapacity::getCapacity)
                        .filter(Objects::nonNull).noneMatch(c -> c >= pax)) {
                    return false;
                }
            }
            if (catalog.getSaleMode() == SalesCatalog.SaleMode.INSTANT) {
                for (LocalDate d : days) {
                    if (STATUS_UNAVAILABLE.equals(instantStatus(r.getTenantId(), d).getOrDefault(r.getId(), STATUS_UNAVAILABLE))) {
                        return false;
                    }
                }
                return true;
            }
            LocalDateTime[] window = window(r, days.get(0), days.get(days.size() - 1));
            return spaceService.isFree(r, window[0], window[1]);
        }

        Map<Long, String> instantStatus(Long tenantId, LocalDate date) {
            return instantStatus.computeIfAbsent(tenantId + "|" + date, k -> {
                AvailabilityResponse response = availabilityService.getAvailability(tenantId, date, null, catalog.getRequestType());
                Map<Long, String> out = new HashMap<>();
                if (response.getResources() != null) {
                    for (AvailabilityResourceDto dto : response.getResources()) {
                        out.put(dto.getId(), dto.getStatus());
                    }
                }
                return out;
            });
        }
    }

    /** From the opening of the first day to the closing of the last day (hotel booking calendar / season hours). */
    LocalDateTime[] window(Resource r, LocalDate first, LocalDate last) {
        try {
            BookingCalendar cal = calendarService.calendarFor(r.getTenantId(), r.getLocation() != null ? r.getLocation().getId() : null);
            return new LocalDateTime[]{calendarService.windowFor(cal, first).open(), calendarService.windowFor(cal, last).close()};
        } catch (IllegalStateException | IllegalArgumentException noCalendar) {
            return new LocalDateTime[]{first.atStartOfDay(), last.plusDays(1).atStartOfDay()};
        }
    }

    // ------------------------------------------------------------------ maps

    /**
     * Maps of the catalog on the date: maps linked to the date's season, otherwise seasonless links whose map is valid
     * on the date (latest {@code valid_from} per location, as availability does). Positions of resources that are not
     * published items of the catalog are {@code selectable = false} with status INACTIVE.
     */
    @Transactional(readOnly = true)
    public List<CatalogDtos.MapView> maps(SalesCatalog catalog, LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date is required");
        }
        DayCheck check = day(catalog, date);
        List<CatalogMap> links = catalogMapRepo.findByCatalogIdOrderByResourceMapIdAsc(catalog.getId());
        if (links.isEmpty()) {
            return List.of();
        }
        Map<Long, ResourceMap> mapsById = mapRepo.findAllById(links.stream().map(CatalogMap::getResourceMapId).toList()).stream()
                .collect(Collectors.toMap(ResourceMap::getId, Function.identity()));
        List<ResourceMap> selected;
        Season season = check.season();
        List<ResourceMap> seasonal = season == null ? List.of() : links.stream()
                .filter(l -> season.getId().equals(l.getSeasonId()))
                .map(l -> mapsById.get(l.getResourceMapId()))
                .filter(Objects::nonNull)
                .toList();
        if (!seasonal.isEmpty()) {
            selected = seasonal;
        } else {
            Map<Long, ResourceMap> byLocation = new java.util.LinkedHashMap<>();
            links.stream()
                    .filter(l -> l.getSeasonId() == null)
                    .map(l -> mapsById.get(l.getResourceMapId()))
                    .filter(Objects::nonNull)
                    .filter(m -> m.getParentMap() == null)
                    .filter(m -> (m.getValidFrom() == null || !date.isBefore(m.getValidFrom()))
                            && (m.getValidTo() == null || !date.isAfter(m.getValidTo())))
                    .sorted(Comparator.comparing(ResourceMap::getValidFrom, Comparator.nullsFirst(Comparator.naturalOrder())).reversed()
                            .thenComparing(ResourceMap::getId))
                    .forEach(m -> byLocation.putIfAbsent(m.getLocationNode() != null ? m.getLocationNode().getId() : -1L, m));
            selected = new ArrayList<>(byLocation.values());
        }
        if (selected.isEmpty()) {
            return List.of();
        }
        Offer offer = offer(catalog);
        Set<Long> selectable = offer.items().stream()
                .filter(i -> i.getItemKind() == CatalogItem.Kind.RESOURCE && Boolean.TRUE.equals(i.getPublished()))
                .filter(offer::visible)
                .filter(i -> check.sellable() && offeredOn(i, date, season, offer.seasonIds(i)))
                .map(CatalogItem::getResourceId)
                .collect(Collectors.toSet());
        Availability availability = new Availability(catalog, List.of(date));
        List<ResourceMapResource> positions = mapResourceRepo.findByResourceMapIdIn(selected.stream().map(ResourceMap::getId).toList());
        Map<Long, List<ResourceMapResource>> byMap = new HashMap<>();
        for (ResourceMapResource pos : positions) {
            byMap.computeIfAbsent(pos.getResourceMap().getId(), k -> new ArrayList<>()).add(pos);
        }
        List<CatalogDtos.MapView> out = new ArrayList<>();
        for (ResourceMap map : selected) {
            List<CatalogDtos.Position> rows = new ArrayList<>();
            for (ResourceMapResource pos : byMap.getOrDefault(map.getId(), List.of())) {
                Resource r = pos.getResource();
                boolean canSelect = r != null && selectable.contains(r.getId());
                String status = STATUS_INACTIVE;
                if (canSelect) {
                    status = statusOf(availability, r);
                }
                rows.add(new CatalogDtos.Position(r != null ? r.getId() : null, pos.getPolygon(), pos.getLabel(), status, canSelect));
            }
            out.add(new CatalogDtos.MapView(map.getId(), map.getName(), map.getImageUrl(), map.getSvgOverlay(), rows));
        }
        return out;
    }

    private String statusOf(Availability availability, Resource r) {
        LocalDate date = availability.days.get(0);
        if (availability.catalog.getSaleMode() == SalesCatalog.SaleMode.INSTANT) {
            return availability.instantStatus(r.getTenantId(), date).getOrDefault(r.getId(), STATUS_UNAVAILABLE);
        }
        LocalDateTime[] window = window(r, date, date);
        return spaceService.isFree(r, window[0], window[1]) ? STATUS_AVAILABLE : STATUS_UNAVAILABLE;
    }

    /** Comma separated groups → upper-case set; null when not filtering. */
    public static Set<String> parseGroups(String group) {
        if (group == null || group.isBlank()) {
            return null;
        }
        return Arrays.stream(group.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
