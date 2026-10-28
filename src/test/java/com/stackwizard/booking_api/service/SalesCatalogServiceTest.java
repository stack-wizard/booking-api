package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.CatalogDtos;
import com.stackwizard.booking_api.model.CatalogItem;
import com.stackwizard.booking_api.model.CrmLookup;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceMap;
import com.stackwizard.booking_api.model.ResourceMapResource;
import com.stackwizard.booking_api.model.ResourceType;
import com.stackwizard.booking_api.model.SalesCatalog;
import com.stackwizard.booking_api.model.SalesQuoteLine;
import com.stackwizard.booking_api.model.Season;
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
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesCatalogServiceTest {
    @Mock SalesCatalogRepository catalogRepo;
    @Mock CatalogItemRepository itemRepo;
    @Mock CatalogItemSeasonRepository itemSeasonRepo;
    @Mock CatalogMapRepository catalogMapRepo;
    @Mock SeasonCalendarRepository calendarRepo;
    @Mock SeasonRepository seasonRepo;
    @Mock ResourceRepository resourceRepo;
    @Mock ProductRepository productRepo;
    @Mock ResourceMapRepository mapRepo;
    @Mock ResourceMapResourceRepository mapResourceRepo;
    @Mock CrmLookupService lookupService;
    @Mock PriceListEntryResolver resolver;
    @Mock CatalogViewService viewService;
    @Mock PricingAccess access;

    SalesCatalogService service;
    SalesCatalog mice = SalesCatalog.builder().id(1L).tenantId(2L).code("MICE").name("MICE")
            .saleMode(SalesCatalog.SaleMode.INQUIRY).requestType(ReservationRequest.Type.EXTERNAL).currency("EUR").active(true).build();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(2L);
        service = new SalesCatalogService(catalogRepo, itemRepo, itemSeasonRepo, catalogMapRepo, calendarRepo, seasonRepo,
                resourceRepo, productRepo, mapRepo, mapResourceRepo, TenantHierarchyTestSupport.standalone(), lookupService,
                resolver, viewService, access);
        when(catalogRepo.findByIdAndTenantId(1L, 2L)).thenReturn(Optional.of(mice));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(catalogRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.findForCatalog(1L)).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    static Resource resource(Long id, String type, Product product) {
        return Resource.builder().id(id).tenantId(2L).name("R" + id).displayOrder(id.intValue()).product(product)
                .resourceType(ResourceType.builder().code(type).build()).build();
    }

    @Test
    void defaultGroups() {
        Product meeting = Product.builder().salesGroup(SalesQuoteLine.Group.MEETING).build();
        assertThat(SalesCatalogService.defaultGroup(resource(1L, "MEETING_ROOM", null))).isEqualTo("HALL");
        assertThat(SalesCatalogService.defaultGroup(resource(1L, "COMPOSITION", meeting))).isEqualTo("HALL");
        assertThat(SalesCatalogService.defaultGroup(resource(1L, "SUNBED", null))).isEqualTo("SPOT");
        assertThat(SalesCatalogService.defaultGroup(resource(1L, "ROOM", null))).isEqualTo("ROOMS");
        assertThat(SalesCatalogService.defaultGroup(Product.builder().salesGroup(SalesQuoteLine.Group.F_AND_B).build())).isEqualTo("CATERING");
        assertThat(SalesCatalogService.defaultGroup(Product.builder().salesGroup(SalesQuoteLine.Group.AV).build())).isEqualTo("AV");
        assertThat(SalesCatalogService.defaultGroup(Product.builder().salesGroup(SalesQuoteLine.Group.EXTRAS).build())).isEqualTo("EXTRAS");
        assertThat(SalesCatalogService.defaultGroup(Product.builder().salesGroup(SalesQuoteLine.Group.ROOMS).build())).isEqualTo("ROOMS");
        assertThat(SalesCatalogService.defaultGroup(Product.builder().packagePricing(Product.PackagePricing.SUM).build())).isEqualTo("PACKAGE");
    }

    @Test
    void addItemFillsGroupAndRejectsDuplicates() {
        when(productRepo.findByIdAndTenantId(116L, 2L)).thenReturn(Optional.of(Product.builder().id(116L).tenantId(2L)
                .name("Coffee break").salesGroup(SalesQuoteLine.Group.F_AND_B).build()));
        CatalogItem saved = service.addItem(1L, CatalogItem.builder().itemKind(CatalogItem.Kind.PRODUCT).productId(116L).build());
        assertThat(saved.getItemGroup()).isEqualTo("CATERING");
        assertThat(saved.getPublished()).isFalse();

        when(itemRepo.findByCatalogIdAndProductId(1L, 116L)).thenReturn(Optional.of(saved));
        assertThatThrownBy(() -> service.addItem(1L, CatalogItem.builder().itemKind(CatalogItem.Kind.PRODUCT).productId(116L).build()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Coffee break is already in catalog MICE");
    }

    @Test
    void addItemValidatesKindAgainstProduct() {
        when(productRepo.findByIdAndTenantId(85L, 2L)).thenReturn(Optional.of(Product.builder().id(85L).tenantId(2L)
                .name("DDR").packagePricing(Product.PackagePricing.SPLIT_PERCENT).build()));
        assertThatThrownBy(() -> service.addItem(1L, CatalogItem.builder().itemKind(CatalogItem.Kind.PRODUCT).productId(85L).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("use itemKind PACKAGE");
        assertThatThrownBy(() -> service.addItem(1L, CatalogItem.builder().itemKind(CatalogItem.Kind.RESOURCE).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("resourceId is required");
        assertThatThrownBy(() -> service.addItem(1L, CatalogItem.builder().build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("itemKind is required");
    }

    @Test
    void createRejectsDuplicateCodeAndUpdateKeepsCode() {
        when(catalogRepo.findByTenantIdAndCodeIgnoreCase(2L, "MICE")).thenReturn(List.of(mice));
        assertThatThrownBy(() -> service.create(new CatalogDtos.CatalogInput("mice", "Again", SalesCatalog.SaleMode.INQUIRY,
                null, null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MICE already exists");
        assertThatThrownBy(() -> service.update(1L, new CatalogDtos.CatalogInput("WEDDING", "x", SalesCatalog.SaleMode.INQUIRY,
                null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("code cannot be changed");
        SalesCatalog created = service.create(new CatalogDtos.CatalogInput("weddings", "Weddings", SalesCatalog.SaleMode.INQUIRY,
                null, null, null, null, null, null));
        assertThat(created.getCode()).isEqualTo("WEDDINGS");
        assertThat(created.getRequestType()).isEqualTo(ReservationRequest.Type.EXTERNAL);
        assertThat(created.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void eventTypesAreCanonicalActiveCodesInGivenOrder() {
        when(lookupService.requireCode(eq(2L), eq(CrmLookup.Kind.EVENT_TYPE), any(), any())).thenAnswer(inv -> {
            String value = ((String) inv.getArgument(2)).trim().toUpperCase();
            if (value.equals(inv.getArgument(3))) {
                return value;
            }
            if (!List.of("CONFERENCE", "PARTY").contains(value)) {
                throw new IllegalArgumentException("Unknown event type: " + value);
            }
            return value;
        });
        mice.setEventTypes(new java.util.ArrayList<>(List.of("WEDDING")));

        SalesCatalog updated = service.update(1L, new CatalogDtos.CatalogInput(null, "MICE", SalesCatalog.SaleMode.INQUIRY,
                null, null, null, null, null, List.of("party", " CONFERENCE", "PARTY", "", "WEDDING")));

        // WEDDING is no longer active but stays because the catalog already had it.
        assertThat(updated.getEventTypes()).containsExactly("PARTY", "CONFERENCE", "WEDDING");
        assertThatThrownBy(() -> service.update(1L, new CatalogDtos.CatalogInput(null, "MICE", SalesCatalog.SaleMode.INQUIRY,
                null, null, null, null, null, List.of("GALA"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventTypes: GALA is not an active event type");
        // null leaves the list alone (older clients).
        assertThat(service.update(1L, new CatalogDtos.CatalogInput(null, "MICE", SalesCatalog.SaleMode.INQUIRY,
                null, null, null, null, null, null)).getEventTypes()).containsExactly("PARTY", "CONFERENCE", "WEDDING");
    }

    @Test
    void bulkFromMapAddsUnpublishedSpotsAndSkipsListed() {
        ResourceMap map = ResourceMap.builder().id(5L).tenantId(2L).name("Beach").build();
        when(mapRepo.findById(5L)).thenReturn(Optional.of(map));
        when(mapRepo.findByTenantId(2L)).thenReturn(List.of(map));
        Resource a = resource(10L, "SUNBED", null);
        Resource b = resource(11L, "SUNBED", null);
        when(mapResourceRepo.findByResourceMapIdIn(List.of(5L))).thenReturn(List.of(
                ResourceMapResource.builder().resource(a).build(), ResourceMapResource.builder().resource(b).build()));
        when(resourceRepo.findById(anyLong())).thenAnswer(inv -> Optional.of(((Long) inv.getArgument(0)) == 10L ? a : b));
        when(itemRepo.findForCatalog(1L)).thenReturn(List.of(CatalogItem.builder().id(1L).resourceId(10L).displayOrder(3).build()));

        CatalogDtos.BulkResult result = service.bulk(1L, new CatalogDtos.BulkRequest(5L, null, null));

        assertThat(result.added()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(i -> {
            assertThat(i.getResourceId()).isEqualTo(11L);
            assertThat(i.getItemGroup()).isEqualTo("SPOT");
            assertThat(i.getPublished()).isFalse();
            assertThat(i.getDisplayOrder()).isEqualTo(4);
        });
        assertThatThrownBy(() -> service.bulk(1L, new CatalogDtos.BulkRequest(5L, List.of(1L), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
    }

    @Test
    void itemSeasonsMustBelongToCatalogCalendar() {
        when(itemRepo.findByIdAndTenantId(41L, 2L)).thenReturn(Optional.of(CatalogItem.builder().id(41L).catalogId(1L).build()));
        assertThatThrownBy(() -> service.setItemSeasons(41L, List.of(10L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no season calendar");
        mice.setSeasonCalendarId(7L);
        when(seasonRepo.findBySeasonCalendarIdOrderByDateFromAscIdAsc(7L)).thenReturn(List.of(Season.builder().id(10L).build()));
        assertThatThrownBy(() -> service.setItemSeasons(41L, List.of(12L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Season 12");
        assertThat(service.setItemSeasons(41L, List.of(10L))).containsExactly(10L);
    }

    @Test
    void reorderRejectsForeignItems() {
        when(itemRepo.findForCatalog(1L)).thenReturn(List.of(CatalogItem.builder().id(1L).displayOrder(0).build(),
                CatalogItem.builder().id(2L).displayOrder(1).build()));
        assertThat(service.reorder(1L, List.of(2L))).extracting(CatalogItem::getId).containsExactly(2L, 1L);
        assertThatThrownBy(() -> service.reorder(1L, List.of(9L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Item 9 is not in catalog MICE");
    }

    @Test
    void mapsValidateSeasonAndDuplicates() {
        when(mapRepo.findById(eq(5L))).thenReturn(Optional.of(ResourceMap.builder().id(5L).tenantId(2L).name("M").build()));
        assertThatThrownBy(() -> service.replaceMaps(1L, List.of(new CatalogDtos.MapLink(5L, null), new CatalogDtos.MapLink(5L, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maps[1]");
        assertThatThrownBy(() -> service.replaceMaps(1L, List.of(new CatalogDtos.MapLink(5L, 10L))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("season 10");
    }

    @Test
    void adminItemRowIsFlatWithGroupField() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        CatalogDtos.AdminItem row = new CatalogDtos.AdminItem(CatalogItem.builder().id(41L).itemKind(CatalogItem.Kind.RESOURCE)
                .resourceId(1386L).itemGroup("HALL").published(true).build(), "Galija", "Galija", null, List.of(), List.of(10L));
        com.fasterxml.jackson.databind.JsonNode json = mapper.valueToTree(row);
        assertThat(json.get("id").asLong()).isEqualTo(41L);
        assertThat(json.get("group").asText()).isEqualTo("HALL");
        assertThat(json.has("itemGroup")).isFalse();
        assertThat(json.has("item")).isFalse();
        assertThat(json.get("sourceName").asText()).isEqualTo("Galija");
        assertThat(json.get("seasonIds").get(0).asLong()).isEqualTo(10L);
        CatalogItem parsed = mapper.readValue("{\"itemKind\":\"PRODUCT\",\"productId\":5,\"group\":\"AV\"}", CatalogItem.class);
        assertThat(parsed.getItemGroup()).isEqualTo("AV");
    }
}
