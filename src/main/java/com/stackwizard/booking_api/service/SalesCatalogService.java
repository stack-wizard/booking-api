package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.CatalogDtos;
import com.stackwizard.booking_api.model.CatalogItem;
import com.stackwizard.booking_api.model.CatalogItemSeason;
import com.stackwizard.booking_api.model.CatalogMap;
import com.stackwizard.booking_api.model.CrmLookup;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceMap;
import com.stackwizard.booking_api.model.ResourceMapResource;
import com.stackwizard.booking_api.model.SalesCatalog;
import com.stackwizard.booking_api.model.SalesQuoteLine;
import com.stackwizard.booking_api.model.Season;
import com.stackwizard.booking_api.model.SeasonCalendar;
import com.stackwizard.booking_api.repository.CatalogItemRepository;
import com.stackwizard.booking_api.repository.CatalogItemSeasonRepository;
import com.stackwizard.booking_api.repository.CatalogMapRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ResourceMapRepository;
import com.stackwizard.booking_api.repository.ResourceMapResourceRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.repository.SalesCatalogRepository;
import com.stackwizard.booking_api.repository.SeasonCalendarRepository;
import com.stackwizard.booking_api.repository.SeasonRepository;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Admin side of sales catalogs: catalogs, items (incl. bulk add, seasons, order), maps, preview. */
@Service
public class SalesCatalogService {
    public static final String GROUP_HALL = "HALL";
    public static final String GROUP_PACKAGE = "PACKAGE";
    public static final String GROUP_SPOT = "SPOT";
    public static final String GROUP_ROOMS = "ROOMS";
    public static final String GROUP_CATERING = "CATERING";
    public static final String GROUP_AV = "AV";
    public static final String GROUP_EXTRAS = "EXTRAS";

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_\\-]{0,39}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Set<String> HALL_TYPES = Set.of("MEETING_ROOM", "MEETING_OFFICE");
    private static final Set<String> ROOM_TYPES = Set.of("ROOM", "APARTMENT");

    private final SalesCatalogRepository catalogRepo;
    private final CatalogItemRepository itemRepo;
    private final CatalogItemSeasonRepository itemSeasonRepo;
    private final CatalogMapRepository catalogMapRepo;
    private final SeasonCalendarRepository calendarRepo;
    private final SeasonRepository seasonRepo;
    private final ResourceRepository resourceRepo;
    private final ProductRepository productRepo;
    private final ResourceMapRepository mapRepo;
    private final ResourceMapResourceRepository mapResourceRepo;
    private final TenantHierarchy tenantHierarchy;
    private final CrmLookupService lookupService;
    private final PriceListEntryResolver resolver;
    private final CatalogViewService viewService;
    private final PricingAccess access;

    public SalesCatalogService(SalesCatalogRepository catalogRepo,
                               CatalogItemRepository itemRepo,
                               CatalogItemSeasonRepository itemSeasonRepo,
                               CatalogMapRepository catalogMapRepo,
                               SeasonCalendarRepository calendarRepo,
                               SeasonRepository seasonRepo,
                               ResourceRepository resourceRepo,
                               ProductRepository productRepo,
                               ResourceMapRepository mapRepo,
                               ResourceMapResourceRepository mapResourceRepo,
                               TenantHierarchy tenantHierarchy,
                               CrmLookupService lookupService,
                               PriceListEntryResolver resolver,
                               CatalogViewService viewService,
                               PricingAccess access) {
        this.catalogRepo = catalogRepo;
        this.itemRepo = itemRepo;
        this.itemSeasonRepo = itemSeasonRepo;
        this.catalogMapRepo = catalogMapRepo;
        this.calendarRepo = calendarRepo;
        this.seasonRepo = seasonRepo;
        this.resourceRepo = resourceRepo;
        this.productRepo = productRepo;
        this.mapRepo = mapRepo;
        this.mapResourceRepo = mapResourceRepo;
        this.tenantHierarchy = tenantHierarchy;
        this.lookupService = lookupService;
        this.resolver = resolver;
        this.viewService = viewService;
        this.access = access;
    }

    // ------------------------------------------------------------------ catalogs

    @Transactional(readOnly = true)
    public List<SalesCatalog> catalogs() {
        access.requireRead();
        return catalogRepo.findByTenantIdOrderByCodeAscIdAsc(TenantResolver.requireOrgTenantId());
    }

    @Transactional(readOnly = true)
    public SalesCatalog catalog(Long id) {
        access.requireRead();
        return requireCatalog(id);
    }

    @Transactional
    public SalesCatalog create(CatalogDtos.CatalogInput input) {
        access.requireWrite("sales catalogs");
        Long org = TenantResolver.requireOrgTenantId();
        if (input == null) {
            throw new IllegalArgumentException("code, name and saleMode are required");
        }
        String code = requireText(input.code(), "code").toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("code may contain only letters, digits, '_' and '-' (max 40), e.g. BEACH");
        }
        Long property = input.propertyTenantId();
        if (property != null && !property.equals(org)) {
            tenantHierarchy.requirePropertyOf(property, org);
        }
        boolean duplicate = catalogRepo.findByTenantIdAndCodeIgnoreCase(org, code).stream()
                .anyMatch(c -> Objects.equals(c.getPropertyTenantId(), property));
        if (duplicate) {
            throw new IllegalStateException("A catalog with code " + code + " already exists"
                    + (property != null ? " for this hotel" : ""));
        }
        SalesCatalog catalog = SalesCatalog.builder()
                .tenantId(org)
                .propertyTenantId(property)
                .code(code)
                .active(true)
                .requestType(ReservationRequest.Type.EXTERNAL)
                .currency("EUR")
                .build();
        apply(catalog, input);
        return catalogRepo.save(catalog);
    }

    @Transactional
    public SalesCatalog update(Long id, CatalogDtos.CatalogInput input) {
        access.requireWrite("sales catalogs");
        SalesCatalog catalog = requireCatalog(id);
        if (input == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (input.code() != null && !input.code().trim().equalsIgnoreCase(catalog.getCode())) {
            throw new IllegalArgumentException("code cannot be changed after creation (the website uses it)");
        }
        if (input.propertyTenantId() != null && !input.propertyTenantId().equals(catalog.getPropertyTenantId())) {
            throw new IllegalArgumentException("propertyTenantId cannot be changed; create a new catalog instead");
        }
        apply(catalog, input);
        return catalogRepo.save(catalog);
    }

    @Transactional
    public void delete(Long id) {
        access.requireWrite("sales catalogs");
        catalogRepo.delete(requireCatalog(id));
    }

    private void apply(SalesCatalog catalog, CatalogDtos.CatalogInput input) {
        catalog.setName(requireText(input.name(), "name"));
        if (input.saleMode() == null) {
            throw new IllegalArgumentException("saleMode is required (INSTANT or INQUIRY)");
        }
        catalog.setSaleMode(input.saleMode());
        if (input.requestType() != null) {
            catalog.setRequestType(input.requestType());
        }
        if (input.currency() != null) {
            String currency = input.currency().trim().toUpperCase(Locale.ROOT);
            if (!CURRENCY.matcher(currency).matches()) {
                throw new IllegalArgumentException("currency must be an ISO 4217 code, e.g. EUR");
            }
            catalog.setCurrency(currency);
        }
        if (input.active() != null) {
            catalog.setActive(input.active());
        }
        if (input.eventTypes() != null) {
            catalog.setEventTypes(eventTypeCodes(catalog, input.eventTypes()));
        }
        if (input.seasonCalendarId() != null) {
            SeasonCalendar calendar = calendarRepo.findByIdAndTenantId(input.seasonCalendarId(), catalog.getTenantId())
                    .orElseThrow(() -> new IllegalArgumentException("seasonCalendarId: season calendar "
                            + input.seasonCalendarId() + " not found"));
            if (calendar.getPropertyTenantId() != null && !calendar.getPropertyTenantId().equals(catalog.getPropertyTenantId())) {
                throw new IllegalArgumentException("seasonCalendarId: calendar '" + calendar.getName()
                        + "' belongs to another hotel; use a chain calendar or one of this catalog's hotel");
            }
            catalog.setSeasonCalendarId(calendar.getId());
        } else {
            catalog.setSeasonCalendarId(null);
        }
    }

    /**
     * Canonical EVENT_TYPE codes in the given order, without duplicates. New codes must be active in the chain's
     * code list; codes the catalog already has are kept even when deactivated since (the website just skips them).
     */
    private List<String> eventTypeCodes(SalesCatalog catalog, List<String> values) {
        Set<String> previous = new HashSet<>();
        catalog.getEventTypes().forEach(code -> previous.add(code.toUpperCase(Locale.ROOT)));
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            String kept = previous.contains(trimmed.toUpperCase(Locale.ROOT)) ? trimmed.toUpperCase(Locale.ROOT) : null;
            String code;
            try {
                code = lookupService.requireCode(catalog.getTenantId(), CrmLookup.Kind.EVENT_TYPE, trimmed, kept);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("eventTypes: " + trimmed
                        + " is not an active event type (Settings → Code lists → Event type)");
            }
            codes.add(code.toUpperCase(Locale.ROOT));
        }
        return new ArrayList<>(codes);
    }

    // ------------------------------------------------------------------ items

    @Transactional(readOnly = true)
    public List<CatalogDtos.AdminItem> items(Long catalogId, String group, Boolean published) {
        access.requireRead();
        SalesCatalog catalog = requireCatalog(catalogId);
        Set<String> groups = CatalogViewService.parseGroups(group);
        CatalogViewService.Offer offer = viewService.offer(catalog);
        List<CatalogDtos.AdminItem> out = new ArrayList<>();
        for (CatalogItem item : offer.items()) {
            if (published != null && !published.equals(item.getPublished())) {
                continue;
            }
            if (groups != null && (item.getItemGroup() == null || !groups.contains(item.getItemGroup().toUpperCase(Locale.ROOT)))) {
                continue;
            }
            out.add(toAdmin(catalog, item, offer));
        }
        return out;
    }

    @Transactional
    public CatalogItem addItem(Long catalogId, CatalogItem input) {
        access.requireWrite("sales catalogs");
        SalesCatalog catalog = requireCatalog(catalogId);
        if (input == null || input.getItemKind() == null) {
            throw new IllegalArgumentException("itemKind is required (RESOURCE, PRODUCT or PACKAGE)");
        }
        CatalogItem item = CatalogItem.builder()
                .tenantId(catalog.getTenantId())
                .catalogId(catalog.getId())
                .itemKind(input.getItemKind())
                .build();
        String defaultGroup;
        if (input.getItemKind() == CatalogItem.Kind.RESOURCE) {
            if (input.getProductId() != null) {
                throw new IllegalArgumentException("productId must be empty for a RESOURCE item (the price comes from the resource's product)");
            }
            Resource resource = requireResource(catalog, input.getResourceId());
            requireNotListed(catalog, resource.getId(), null, resource.getName());
            item.setResourceId(resource.getId());
            defaultGroup = defaultGroup(resource);
        } else {
            if (input.getResourceId() != null) {
                throw new IllegalArgumentException("resourceId must be empty for a " + input.getItemKind() + " item");
            }
            Product product = requireProduct(catalog, input.getProductId(), input.getItemKind());
            requireNotListed(catalog, null, product.getId(), product.getName());
            item.setProductId(product.getId());
            defaultGroup = input.getItemKind() == CatalogItem.Kind.PACKAGE ? GROUP_PACKAGE : defaultGroup(product);
        }
        item.setItemGroup(blankToNull(input.getItemGroup()) != null ? input.getItemGroup().trim() : defaultGroup);
        item.setDisplayOrder(input.getDisplayOrder() != null ? input.getDisplayOrder() : nextOrder(catalog.getId()));
        item.setPublished(Boolean.TRUE.equals(input.getPublished()));
        applyEditable(item, input);
        item.setUpdatedAt(OffsetDateTime.now());
        return itemRepo.save(item);
    }

    @Transactional
    public CatalogItem updateItem(Long itemId, CatalogItem input) {
        access.requireWrite("sales catalogs");
        CatalogItem item = requireItem(itemId);
        if (input == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (input.getItemKind() != null && input.getItemKind() != item.getItemKind()
                || input.getResourceId() != null && !input.getResourceId().equals(item.getResourceId())
                || input.getProductId() != null && !input.getProductId().equals(item.getProductId())) {
            throw new IllegalArgumentException("itemKind, resourceId and productId cannot be changed; add a new item instead");
        }
        if (input.getItemGroup() != null) {
            item.setItemGroup(blankToNull(input.getItemGroup()));
        }
        if (input.getDisplayOrder() != null) {
            item.setDisplayOrder(input.getDisplayOrder());
        }
        if (input.getPublished() != null) {
            item.setPublished(input.getPublished());
        }
        applyEditable(item, input);
        item.setUpdatedAt(OffsetDateTime.now());
        return itemRepo.save(item);
    }

    @Transactional
    public void deleteItem(Long itemId) {
        access.requireWrite("sales catalogs");
        itemRepo.delete(requireItem(itemId));
    }

    private void applyEditable(CatalogItem item, CatalogItem input) {
        if (input.getValidFrom() != null && input.getValidTo() != null && input.getValidTo().isBefore(input.getValidFrom())) {
            throw new IllegalArgumentException("validTo must not be before validFrom");
        }
        if (input.getMinPax() != null && input.getMinPax() <= 0 || input.getMaxPax() != null && input.getMaxPax() <= 0) {
            throw new IllegalArgumentException("minPax and maxPax must be > 0");
        }
        if (input.getMinPax() != null && input.getMaxPax() != null && input.getMaxPax() < input.getMinPax()) {
            throw new IllegalArgumentException("maxPax must be >= minPax");
        }
        item.setPublicName(blankToNull(input.getPublicName()));
        item.setPublicDescription(blankToNull(input.getPublicDescription()));
        item.setValidFrom(input.getValidFrom());
        item.setValidTo(input.getValidTo());
        item.setMinPax(input.getMinPax());
        item.setMaxPax(input.getMaxPax());
        item.setDuration(input.getDuration());
        item.setDefaultStartTime(input.getDefaultStartTime());
        item.setSetupStyle(lookupService.requireCode(item.getTenantId(), CrmLookup.Kind.SETUP_STYLE,
                input.getSetupStyle(), item.getSetupStyle()));
    }

    /**
     * Adds many items at once, unpublished: every resource on a map (and its sub-maps), a list of resources, or a list
     * of products (packages become PACKAGE items). Already listed ones are skipped.
     */
    @Transactional
    public CatalogDtos.BulkResult bulk(Long catalogId, CatalogDtos.BulkRequest request) {
        access.requireWrite("sales catalogs");
        SalesCatalog catalog = requireCatalog(catalogId);
        if (request == null) {
            throw new IllegalArgumentException("Send resourceMapId, resourceIds or productIds");
        }
        int sources = (request.resourceMapId() != null ? 1 : 0)
                + (request.resourceIds() != null && !request.resourceIds().isEmpty() ? 1 : 0)
                + (request.productIds() != null && !request.productIds().isEmpty() ? 1 : 0);
        if (sources != 1) {
            throw new IllegalArgumentException("Send exactly one of resourceMapId, resourceIds or productIds");
        }
        Map<Long, CatalogItem> byResource = new HashMap<>();
        Map<Long, CatalogItem> byProduct = new HashMap<>();
        for (CatalogItem existing : itemRepo.findForCatalog(catalog.getId())) {
            if (existing.getResourceId() != null) {
                byResource.put(existing.getResourceId(), existing);
            }
            if (existing.getProductId() != null) {
                byProduct.put(existing.getProductId(), existing);
            }
        }
        int order = nextOrder(catalog.getId());
        int skipped = 0;
        List<CatalogItem> added = new ArrayList<>();
        if (request.productIds() != null && !request.productIds().isEmpty()) {
            for (Long productId : new LinkedHashSet<>(request.productIds())) {
                Product product = productRepo.findByIdAndTenantId(productId, catalog.getTenantId())
                        .orElseThrow(() -> new IllegalArgumentException("productIds: product " + productId + " not found"));
                if (byProduct.containsKey(product.getId())) {
                    skipped++;
                    continue;
                }
                CatalogItem.Kind kind = product.getPackagePricing() != null ? CatalogItem.Kind.PACKAGE : CatalogItem.Kind.PRODUCT;
                added.add(newItem(catalog, kind, null, product.getId(),
                        kind == CatalogItem.Kind.PACKAGE ? GROUP_PACKAGE : defaultGroup(product), order++));
            }
        } else {
            List<Long> resourceIds = request.resourceMapId() != null
                    ? resourcesOnMap(catalog, request.resourceMapId())
                    : new ArrayList<>(new LinkedHashSet<>(request.resourceIds()));
            for (Long resourceId : resourceIds) {
                Resource resource = requireResource(catalog, resourceId);
                if (byResource.containsKey(resource.getId())) {
                    skipped++;
                    continue;
                }
                String group = request.resourceMapId() != null && !isHall(resource) ? GROUP_SPOT : defaultGroup(resource);
                added.add(newItem(catalog, CatalogItem.Kind.RESOURCE, resource.getId(), null, group, order++));
            }
        }
        List<CatalogItem> saved = itemRepo.saveAll(added);
        return new CatalogDtos.BulkResult(saved.size(), skipped, saved);
    }

    private CatalogItem newItem(SalesCatalog catalog, CatalogItem.Kind kind, Long resourceId, Long productId, String group, int order) {
        return CatalogItem.builder()
                .tenantId(catalog.getTenantId())
                .catalogId(catalog.getId())
                .itemKind(kind)
                .resourceId(resourceId)
                .productId(productId)
                .itemGroup(group)
                .displayOrder(order)
                .published(false)
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private List<Long> resourcesOnMap(SalesCatalog catalog, Long mapId) {
        ResourceMap root = requireMap(catalog, mapId, "resourceMapId");
        List<Long> mapIds = new ArrayList<>();
        mapIds.add(root.getId());
        List<ResourceMap> all = mapRepo.findByTenantId(root.getTenantId());
        boolean grew = true;
        while (grew) {
            grew = false;
            for (ResourceMap m : all) {
                if (m.getParentMap() != null && mapIds.contains(m.getParentMap().getId()) && !mapIds.contains(m.getId())) {
                    mapIds.add(m.getId());
                    grew = true;
                }
            }
        }
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        mapResourceRepo.findByResourceMapIdIn(mapIds).stream()
                .map(ResourceMapResource::getResource)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Resource::getDisplayOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Resource::getId))
                .forEach(r -> out.add(r.getId()));
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Map '" + root.getName() + "' has no resources");
        }
        return new ArrayList<>(out);
    }

    /** Limits the item to seasons of the catalog's calendar; empty = every season. */
    @Transactional
    public List<Long> setItemSeasons(Long itemId, List<Long> seasonIds) {
        access.requireWrite("sales catalogs");
        CatalogItem item = requireItem(itemId);
        SalesCatalog catalog = requireCatalog(item.getCatalogId());
        Set<Long> ids = seasonIds == null ? Set.of() : new LinkedHashSet<>(seasonIds);
        if (!ids.isEmpty()) {
            if (catalog.getSeasonCalendarId() == null) {
                throw new IllegalArgumentException("Catalog " + catalog.getCode() + " has no season calendar; set one first");
            }
            Set<Long> valid = new HashSet<>();
            seasonRepo.findBySeasonCalendarIdOrderByDateFromAscIdAsc(catalog.getSeasonCalendarId()).forEach(s -> valid.add(s.getId()));
            for (Long id : ids) {
                if (id == null || !valid.contains(id)) {
                    throw new IllegalArgumentException("Season " + id + " is not in the catalog's season calendar");
                }
            }
        }
        itemSeasonRepo.deleteByCatalogItemId(item.getId());
        itemSeasonRepo.flush();
        itemSeasonRepo.saveAll(ids.stream().map(id -> new CatalogItemSeason(item.getId(), id)).toList());
        return new ArrayList<>(ids);
    }

    /** Drag & drop order: listed items get 0..n-1, the rest follow in their current order. */
    @Transactional
    public List<CatalogItem> reorder(Long catalogId, List<Long> itemIds) {
        access.requireWrite("sales catalogs");
        SalesCatalog catalog = requireCatalog(catalogId);
        if (itemIds == null || itemIds.isEmpty()) {
            throw new IllegalArgumentException("Send the item ids in the new order");
        }
        List<CatalogItem> items = itemRepo.findForCatalog(catalog.getId());
        Map<Long, CatalogItem> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.getId(), i));
        Set<Long> seen = new HashSet<>();
        List<CatalogItem> ordered = new ArrayList<>();
        for (Long id : itemIds) {
            CatalogItem item = byId.get(id);
            if (item == null) {
                throw new IllegalArgumentException("Item " + id + " is not in catalog " + catalog.getCode());
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("Item " + id + " is listed twice");
            }
            ordered.add(item);
        }
        items.stream().filter(i -> !seen.contains(i.getId())).forEach(ordered::add);
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setDisplayOrder(i);
        }
        itemRepo.saveAll(ordered);
        return ordered;
    }

    // ------------------------------------------------------------------ maps

    @Transactional(readOnly = true)
    public List<CatalogDtos.MapLink> maps(Long catalogId) {
        access.requireRead();
        SalesCatalog catalog = requireCatalog(catalogId);
        return catalogMapRepo.findByCatalogIdOrderByResourceMapIdAsc(catalog.getId()).stream()
                .map(m -> new CatalogDtos.MapLink(m.getResourceMapId(), m.getSeasonId()))
                .toList();
    }

    @Transactional
    public List<CatalogDtos.MapLink> replaceMaps(Long catalogId, List<CatalogDtos.MapLink> links) {
        access.requireWrite("sales catalogs");
        SalesCatalog catalog = requireCatalog(catalogId);
        List<CatalogDtos.MapLink> input = links == null ? List.of() : links;
        Set<Long> seasonIds = new HashSet<>();
        if (catalog.getSeasonCalendarId() != null) {
            seasonRepo.findBySeasonCalendarIdOrderByDateFromAscIdAsc(catalog.getSeasonCalendarId()).forEach(s -> seasonIds.add(s.getId()));
        }
        Set<Long> seen = new HashSet<>();
        List<CatalogMap> rows = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            CatalogDtos.MapLink link = input.get(i);
            String at = "maps[" + i + "]";
            if (link == null || link.resourceMapId() == null) {
                throw new IllegalArgumentException(at + ": resourceMapId is required");
            }
            if (!seen.add(link.resourceMapId())) {
                throw new IllegalArgumentException(at + ": map " + link.resourceMapId() + " is listed twice");
            }
            requireMap(catalog, link.resourceMapId(), at + ".resourceMapId");
            if (link.seasonId() != null && !seasonIds.contains(link.seasonId())) {
                throw new IllegalArgumentException(at + ": season " + link.seasonId() + " is not in the catalog's season calendar");
            }
            rows.add(new CatalogMap(catalog.getId(), link.resourceMapId(), link.seasonId()));
        }
        catalogMapRepo.deleteByCatalogId(catalog.getId());
        catalogMapRepo.flush();
        catalogMapRepo.saveAll(rows);
        return maps(catalogId);
    }

    // ------------------------------------------------------------------ preview / product card

    /** What the web would get for the date ({@code /items} + {@code /maps}), including unsold days with the reason. */
    @Transactional(readOnly = true)
    public CatalogDtos.Preview preview(Long catalogId, LocalDate date, Integer pax) {
        access.requireRead();
        SalesCatalog catalog = requireCatalog(catalogId);
        if (date == null) {
            throw new IllegalArgumentException("date is required");
        }
        CatalogViewService.DayCheck check = viewService.day(catalog, date);
        return new CatalogDtos.Preview(date, CatalogViewService.toView(check.season()), check.sellable(), check.reason(),
                viewService.items(catalog, date, null, pax, null), viewService.maps(catalog, date));
    }

    @Transactional(readOnly = true)
    public List<CatalogDtos.ProductCatalogItem> productCatalogItems(Long productId) {
        access.requireRead();
        Long org = TenantResolver.requireOrgTenantId();
        productRepo.findByIdAndTenantId(productId, org)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        Map<Long, SalesCatalog> catalogs = new HashMap<>();
        catalogRepo.findByTenantIdOrderByCodeAscIdAsc(org).forEach(c -> catalogs.put(c.getId(), c));
        List<CatalogDtos.ProductCatalogItem> out = new ArrayList<>();
        for (CatalogItem item : itemRepo.findByTenantIdAndProductIdOrderByCatalogIdAsc(org, productId)) {
            SalesCatalog c = catalogs.get(item.getCatalogId());
            if (c != null) {
                out.add(new CatalogDtos.ProductCatalogItem(c.getId(), c.getCode(), c.getName(), item));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private CatalogDtos.AdminItem toAdmin(SalesCatalog catalog, CatalogItem item, CatalogViewService.Offer offer) {
        Resource r = offer.resource(item);
        Product p = offer.product(item);
        String source = r != null ? r.getName() : p != null ? p.getName() : null;
        String display = item.getPublicName() != null ? item.getPublicName() : source;
        String image = p != null ? p.getDefaultImageUrl() : null;
        List<CatalogDtos.Price> prices = new ArrayList<>();
        if (p != null && item.getItemKind() != CatalogItem.Kind.PACKAGE) {
            for (String uom : CatalogViewService.uoms(p)) {
                resolver.resolve(p.getId(), uom, catalog.getCurrency(), offer.priceTenant(item), LocalDate.now(), null, catalog.getRequestType())
                        .ifPresent(rp -> prices.add(new CatalogDtos.Price(uom, rp.price(), catalog.getCurrency(),
                                CatalogViewService.webRule(rp.rule()))));
            }
        } else if (p != null) {
            java.math.BigDecimal ppp = viewService.packagePricePerPerson(catalog, item, offer, LocalDate.now(), null);
            if (ppp != null) {
                prices.add(new CatalogDtos.Price("PAX", ppp, catalog.getCurrency(), null));
            }
        }
        return new CatalogDtos.AdminItem(item, source, display, image, prices, new ArrayList<>(offer.seasonIds(item)));
    }

    static String defaultGroup(Resource resource) {
        if (isHall(resource)) {
            return GROUP_HALL;
        }
        String type = typeCode(resource);
        return ROOM_TYPES.contains(type) ? GROUP_ROOMS : GROUP_SPOT;
    }

    static boolean isHall(Resource resource) {
        Product p = resource.getProduct();
        return HALL_TYPES.contains(typeCode(resource)) || p != null && p.getSalesGroup() == SalesQuoteLine.Group.MEETING;
    }

    static String defaultGroup(Product product) {
        if (product.getPackagePricing() != null) {
            return GROUP_PACKAGE;
        }
        if (product.getSalesGroup() == null) {
            return null;
        }
        return switch (product.getSalesGroup()) {
            case F_AND_B -> GROUP_CATERING;
            case AV -> GROUP_AV;
            case EXTRAS -> GROUP_EXTRAS;
            case ROOMS -> GROUP_ROOMS;
            case MEETING -> GROUP_HALL;
            case DISCOUNT -> null;
        };
    }

    private static String typeCode(Resource resource) {
        return resource.getResourceType() != null && resource.getResourceType().getCode() != null
                ? resource.getResourceType().getCode().trim().toUpperCase(Locale.ROOT) : "";
    }

    private Resource requireResource(SalesCatalog catalog, Long resourceId) {
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId is required for a RESOURCE item");
        }
        Resource resource = resourceRepo.findById(resourceId)
                .filter(r -> tenantHierarchy.familyIdsOf(catalog.getTenantId()).contains(r.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Resource not found: " + resourceId));
        if (catalog.getPropertyTenantId() != null && !catalog.getPropertyTenantId().equals(resource.getTenantId())) {
            throw new IllegalArgumentException("Resource " + resource.getName() + " belongs to another hotel than catalog " + catalog.getCode());
        }
        return resource;
    }

    private Product requireProduct(SalesCatalog catalog, Long productId, CatalogItem.Kind kind) {
        if (productId == null) {
            throw new IllegalArgumentException("productId is required for a " + kind + " item");
        }
        Product product = productRepo.findByIdAndTenantId(productId, catalog.getTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
        if (kind == CatalogItem.Kind.PACKAGE && product.getPackagePricing() == null) {
            throw new IllegalArgumentException(product.getName() + " is not a package; use itemKind PRODUCT");
        }
        if (kind == CatalogItem.Kind.PRODUCT && product.getPackagePricing() != null) {
            throw new IllegalArgumentException(product.getName() + " is a package; use itemKind PACKAGE");
        }
        return product;
    }

    private void requireNotListed(SalesCatalog catalog, Long resourceId, Long productId, String name) {
        boolean exists = resourceId != null
                ? itemRepo.findByCatalogIdAndResourceId(catalog.getId(), resourceId).isPresent()
                : itemRepo.findByCatalogIdAndProductId(catalog.getId(), productId).isPresent();
        if (exists) {
            throw new IllegalStateException(name + " is already in catalog " + catalog.getCode());
        }
    }

    private ResourceMap requireMap(SalesCatalog catalog, Long mapId, String field) {
        ResourceMap map = mapRepo.findById(mapId)
                .filter(m -> tenantHierarchy.familyIdsOf(catalog.getTenantId()).contains(m.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException(field + ": resource map " + mapId + " not found"));
        if (catalog.getPropertyTenantId() != null && !catalog.getPropertyTenantId().equals(map.getTenantId())) {
            throw new IllegalArgumentException(field + ": map '" + map.getName() + "' belongs to another hotel");
        }
        return map;
    }

    private int nextOrder(Long catalogId) {
        return itemRepo.findForCatalog(catalogId).stream()
                .map(CatalogItem::getDisplayOrder)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .map(o -> o + 1)
                .orElse(0);
    }

    SalesCatalog requireCatalog(Long id) {
        return catalogRepo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sales catalog not found: " + id));
    }

    private CatalogItem requireItem(Long id) {
        return itemRepo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalog item not found: " + id));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
