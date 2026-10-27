package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.CatalogDtos;
import com.stackwizard.booking_api.model.CatalogItem;
import com.stackwizard.booking_api.model.CatalogItemSeason;
import com.stackwizard.booking_api.model.CatalogMap;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.PriceProfileDate;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ReservationRequest;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Catalog resolution (seasons, offer, maps) and per-day quotes. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogViewAndQuoteServiceTest {
    static final LocalDate THU = LocalDate.of(2027, 5, 13);
    static final LocalDate FRI = LocalDate.of(2027, 5, 14);

    @Mock SalesCatalogRepository catalogRepo;
    @Mock CatalogItemRepository itemRepo;
    @Mock CatalogItemSeasonRepository itemSeasonRepo;
    @Mock CatalogMapRepository catalogMapRepo;
    @Mock SeasonRepository seasonRepo;
    @Mock ResourceRepository resourceRepo;
    @Mock ProductRepository productRepo;
    @Mock ResourceMapRepository mapRepo;
    @Mock ResourceMapResourceRepository mapResourceRepo;
    @Mock ResourceSetupCapacityRepository capacityRepo;
    @Mock PriceListEntryResolver resolver;
    @Mock PackageService packageService;
    @Mock PackageCatalogService packageCatalogService;
    @Mock ProductVisibilityService visibility;
    @Mock ResourceSetupCapacityService spaceService;
    @Mock AvailabilityService availabilityService;
    @Mock ServiceCalendarService calendarService;

    CatalogViewService view;
    CatalogQuoteService quotes;

    SalesCatalog mice = SalesCatalog.builder().id(1L).tenantId(2L).code("MICE").name("Meetings")
            .saleMode(SalesCatalog.SaleMode.INQUIRY).seasonCalendarId(7L).requestType(ReservationRequest.Type.EXTERNAL)
            .currency("EUR").active(true).build();
    Season pre = Season.builder().id(10L).seasonCalendarId(7L).code("PRE_2027").name("Pre")
            .dateFrom(LocalDate.of(2027, 5, 1)).dateTo(LocalDate.of(2027, 6, 14)).status(Season.Status.OPEN).build();
    Season closed = Season.builder().id(11L).seasonCalendarId(7L).code("CLOSED_2027").name("Renovation")
            .dateFrom(LocalDate.of(2027, 6, 15)).dateTo(LocalDate.of(2027, 6, 30)).status(Season.Status.CLOSED).build();

    Product hallRent = product(110L, "Galija rent", "DAY", Set.of("HALF_DAY"), "25");
    Product coffee = product(116L, "Coffee break", "PAX", Set.of(), "13");
    Product av = product(117L, "Projector", "UNIT", Set.of(), "25");
    Product flowers = product(118L, "Flowers", "UNIT", Set.of(), "25");
    Resource galija = Resource.builder().id(1386L).tenantId(3L).name("Galija").product(hallRent)
            .areaSqm(new BigDecimal("520")).naturalLight(true).build();
    Resource other = Resource.builder().id(1390L).tenantId(3L).name("Sala 2").build();

    CatalogItem hall = item(41L, CatalogItem.Kind.RESOURCE, 1386L, null, "HALL", true);
    CatalogItem coffeeItem = item(42L, CatalogItem.Kind.PRODUCT, null, 116L, "CATERING", true);
    CatalogItem hidden = item(43L, CatalogItem.Kind.PRODUCT, null, 118L, "EXTRAS", false);
    CatalogItem avItem = item(44L, CatalogItem.Kind.PRODUCT, null, 117L, "AV", true);
    CatalogItem expiring = item(45L, CatalogItem.Kind.PRODUCT, null, 118L, "EXTRAS", true);

    @BeforeEach
    void setUp() {
        TenantContext.setScope(2L, null);
        view = new CatalogViewService(catalogRepo, itemRepo, itemSeasonRepo, catalogMapRepo, seasonRepo, resourceRepo, productRepo,
                mapRepo, mapResourceRepo, capacityRepo, resolver, packageService, packageCatalogService, visibility, spaceService,
                availabilityService, calendarService);
        quotes = new CatalogQuoteService(view, resolver);
        expiring.setValidTo(LocalDate.of(2027, 5, 13));
        expiring.setProductId(119L);
        hall.setPublicName("Galija hall");

        when(seasonRepo.findOnDate(eq(7L), any())).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(1);
            return List.of(pre, closed).stream().filter(s -> s.contains(d)).findFirst();
        });
        when(itemRepo.findForCatalog(1L)).thenReturn(List.of(hall, coffeeItem, hidden, avItem, expiring));
        when(resourceRepo.findAllById(any())).thenReturn(List.of(galija, other));
        Product expiringProduct = product(119L, "Late flowers", "UNIT", Set.of(), "25");
        when(productRepo.findAllById(any())).thenReturn(List.of(coffee, av, flowers, expiringProduct));
        when(itemSeasonRepo.findByCatalogItemIdIn(any())).thenReturn(List.of(new CatalogItemSeason(44L, 99L)));
        when(capacityRepo.findByTenantIdAndResourceIdOrderBySetupStyleAsc(3L, 1386L)).thenReturn(List.of(
                ResourceSetupCapacity.builder().setupStyle("THEATER").capacity(80).build()));
        when(spaceService.isFree(any(), any(), any())).thenReturn(true);
        when(calendarService.calendarFor(anyLong(), any())).thenThrow(new IllegalStateException("no calendar"));

        // hall: 1800 on weekdays, 2000 on Fri/Sat; coffee 6.50 pax; projector and flowers have no price
        when(resolver.resolve(eq(110L), eq("DAY"), eq("EUR"), eq(3L), any(), any(), eq(ReservationRequest.Type.EXTERNAL)))
                .thenAnswer(inv -> {
                    LocalDate d = inv.getArgument(4);
                    boolean weekend = d.getDayOfWeek() == DayOfWeek.FRIDAY || d.getDayOfWeek() == DayOfWeek.SATURDAY;
                    return Optional.of(resolved(weekend ? "2000.00" : "1800.00", weekend ? "FRI,SAT" : null));
                });
        when(resolver.resolve(eq(110L), eq("HALF_DAY"), anyString(), anyLong(), any(), any(), any()))
                .thenReturn(Optional.of(resolved("1000.00", null)));
        when(resolver.resolve(eq(116L), eq("PAX"), anyString(), anyLong(), any(), any(), any()))
                .thenReturn(Optional.of(resolved("6.50", null)));
        when(resolver.resolve(eq(118L), anyString(), anyString(), anyLong(), any(), any(), any())).thenReturn(Optional.empty());
        when(resolver.resolve(eq(119L), anyString(), anyString(), anyLong(), any(), any(), any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    static Product product(Long id, String name, String uom, Set<String> extra, String tax) {
        return Product.builder().id(id).tenantId(2L).name(name).defaultUom(uom).extraUoms(new java.util.HashSet<>(extra))
                .tax1Percent(new BigDecimal(tax)).tax2Percent(BigDecimal.ZERO).images(new ArrayList<>())
                .propertyVisibility(Product.PropertyVisibility.ALL).build();
    }

    static CatalogItem item(Long id, CatalogItem.Kind kind, Long resourceId, Long productId, String group, boolean published) {
        return CatalogItem.builder().id(id).tenantId(2L).catalogId(1L).itemKind(kind).resourceId(resourceId)
                .productId(productId).itemGroup(group).published(published).displayOrder(id.intValue()).build();
    }

    static PriceListEntryResolver.ResolvedPrice resolved(String price, String days) {
        PriceListEntryResolver.AppliedRule rule = new PriceListEntryResolver.AppliedRule(1L, 6L, "Events", "CHAIN", null,
                21L, null, null, null, PriceProfileDate.PeriodKind.SEASON, 10L, "PRE_2027", days, null, null);
        return new PriceListEntryResolver.ResolvedPrice(PriceListEntry.builder().price(new BigDecimal(price)).build(), rule);
    }

    // ------------------------------------------------------------------ catalog offer

    @Test
    void itemsWithoutDateListPublishedVisibleItemsWithNullAvailability() {
        List<CatalogDtos.ItemView> items = view.items(mice, null, null, null, null);
        assertThat(items).extracting(CatalogDtos.ItemView::itemId).containsExactly(41L, 42L, 44L, 45L);
        assertThat(items).allMatch(i -> i.available() == null);
        CatalogDtos.ItemView hallView = items.get(0);
        assertThat(hallView.name()).isEqualTo("Galija hall");
        assertThat(hallView.group()).isEqualTo("HALL");
        assertThat(hallView.productId()).isEqualTo(110L);
        assertThat(hallView.areaSqm()).isEqualByComparingTo("520");
        assertThat(hallView.setups()).extracting(CatalogDtos.Setup::capacity).containsExactly(80);
        CatalogDtos.ItemView coffeeView = items.get(1);
        assertThat(coffeeView.resourceId()).isNull();
        assertThat(coffeeView.setups()).isNull();
        assertThat(coffeeView.prices()).singleElement().satisfies(p -> {
            assertThat(p.uom()).isEqualTo("PAX");
            assertThat(p.amountGross()).isEqualByComparingTo("6.50");
            assertThat(p.rule().season()).isEqualTo("PRE_2027");
        });
    }

    @Test
    void itemsOnDateDropUnpublishedOutOfSeasonAndExpiredItems() {
        List<CatalogDtos.ItemView> items = view.items(mice, FRI, null, 60, null);
        // 43 unpublished, 44 limited to another season, 45 valid only until Thursday
        assertThat(items).extracting(CatalogDtos.ItemView::itemId).containsExactly(41L, 42L);
        assertThat(items).allMatch(i -> Boolean.TRUE.equals(i.available()));
        assertThat(items.get(0).prices()).filteredOn(p -> p.uom().equals("DAY")).singleElement()
                .satisfies(p -> assertThat(p.amountGross()).isEqualByComparingTo("2000.00"));
    }

    @Test
    void hallTooSmallForPaxIsNotAvailable() {
        List<CatalogDtos.ItemView> items = view.items(mice, THU, null, 120, Set.of("HALL"));
        assertThat(items).singleElement().satisfies(i -> assertThat(i.available()).isFalse());
    }

    @Test
    void groupFilterAcceptsCommaSeparatedList() {
        assertThat(view.items(mice, THU, null, null, CatalogViewService.parseGroups("hall, catering")))
                .extracting(CatalogDtos.ItemView::group).containsExactly("HALL", "CATERING");
    }

    @Test
    void closedSeasonOrNoSeasonIsNotSellable() {
        assertThat(view.day(mice, LocalDate.of(2027, 6, 20)).sellable()).isFalse();
        assertThat(view.day(mice, LocalDate.of(2027, 6, 20)).reason()).contains("closed season 'Renovation'");
        assertThat(view.day(mice, LocalDate.of(2027, 8, 1)).sellable()).isFalse();
        assertThat(view.items(mice, LocalDate.of(2027, 6, 20), null, null, null)).isEmpty();
        assertThat(view.items(mice, LocalDate.of(2027, 6, 13), LocalDate.of(2027, 6, 16), null, null)).isEmpty();
        SalesCatalog noCalendar = SalesCatalog.builder().id(1L).seasonCalendarId(null).build();
        assertThat(view.day(noCalendar, LocalDate.of(2030, 1, 1)).sellable()).isTrue();
    }

    @Test
    void sameProductInTwoCatalogsKeepsItsOwnText() {
        SalesCatalog weddings = SalesCatalog.builder().id(2L).tenantId(2L).code("WEDDING").currency("EUR")
                .requestType(ReservationRequest.Type.EXTERNAL).saleMode(SalesCatalog.SaleMode.INQUIRY).build();
        CatalogItem weddingCoffee = item(60L, CatalogItem.Kind.PRODUCT, null, 116L, "CATERING", true);
        weddingCoffee.setCatalogId(2L);
        weddingCoffee.setPublicName("Welcome coffee");
        coffeeItem.setPublicName("Conference coffee break");
        when(itemRepo.findForCatalog(2L)).thenReturn(List.of(weddingCoffee));
        assertThat(view.items(weddings, null, null, null, null)).extracting(CatalogDtos.ItemView::name)
                .containsExactly("Welcome coffee");
        assertThat(view.items(mice, null, null, null, Set.of("CATERING"))).extracting(CatalogDtos.ItemView::name)
                .containsExactly("Conference coffee break");
    }

    @Test
    void hotelFilterKeepsOnlyThatHotelsResourcesAndOfferedProducts() {
        TenantContext.setScope(2L, 4L);
        when(visibility.isVisible(any(), eq(4L))).thenAnswer(inv -> ((Product) inv.getArgument(0)).getId() == 116L);
        assertThat(view.items(mice, null, null, null, null)).extracting(CatalogDtos.ItemView::itemId).containsExactly(42L);
    }

    @Test
    void mapMarksResourcesThatAreNotPublishedItemsInactive() {
        ResourceMap map = ResourceMap.builder().id(5L).tenantId(3L).name("Congress floor")
                .validFrom(LocalDate.of(2027, 1, 1)).build();
        when(catalogMapRepo.findByCatalogIdOrderByResourceMapIdAsc(1L)).thenReturn(List.of(new CatalogMap(1L, 5L, null)));
        when(mapRepo.findAllById(any())).thenReturn(List.of(map));
        when(mapResourceRepo.findByResourceMapIdIn(List.of(5L))).thenReturn(List.of(
                ResourceMapResource.builder().resourceMap(map).resource(galija).polygon("1,1 2,2").label("G").build(),
                ResourceMapResource.builder().resourceMap(map).resource(other).polygon("3,3 4,4").label("S2").build()));

        List<CatalogDtos.MapView> maps = view.maps(mice, THU);

        assertThat(maps).singleElement().satisfies(m -> {
            assertThat(m.mapId()).isEqualTo(5L);
            assertThat(m.positions()).extracting(CatalogDtos.Position::resourceId, CatalogDtos.Position::selectable,
                            CatalogDtos.Position::status)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(1386L, true, "AVAILABLE"),
                            org.assertj.core.groups.Tuple.tuple(1390L, false, "INACTIVE"));
        });
    }

    @Test
    void seasonSpecificMapWinsOverValidityMap() {
        ResourceMap generic = ResourceMap.builder().id(5L).tenantId(3L).name("Generic").build();
        ResourceMap summer = ResourceMap.builder().id(6L).tenantId(3L).name("Summer").build();
        when(catalogMapRepo.findByCatalogIdOrderByResourceMapIdAsc(1L)).thenReturn(List.of(
                new CatalogMap(1L, 5L, null), new CatalogMap(1L, 6L, 10L)));
        when(mapRepo.findAllById(any())).thenReturn(List.of(generic, summer));
        when(mapResourceRepo.findByResourceMapIdIn(any())).thenReturn(List.of());
        assertThat(view.maps(mice, THU)).extracting(CatalogDtos.MapView::mapId).containsExactly(6L);
    }

    // ------------------------------------------------------------------ quote

    @Test
    void quoteGivesOneLinePerDistinctDayPriceAndDefaultQuantities() {
        CatalogDtos.QuoteResponse quote = quotes.quote(mice, new CatalogDtos.QuoteRequest(THU, FRI, 60,
                CatalogDtos.DayFormat.FULL_DAY, List.of(new CatalogDtos.QuoteItem(41L, null), new CatalogDtos.QuoteItem(42L, null))));

        assertThat(quote.currency()).isEqualTo("EUR");
        assertThat(quote.lines()).hasSize(3);
        CatalogDtos.QuoteLine thu = quote.lines().get(0);
        assertThat(thu.date()).isEqualTo(THU);
        assertThat(thu.uom()).isEqualTo("DAY");
        assertThat(thu.quantity()).isEqualByComparingTo("1");
        assertThat(thu.unitPriceGross()).isEqualByComparingTo("1800.00");
        assertThat(thu.rule().daysOfWeek()).isNull();
        CatalogDtos.QuoteLine fri = quote.lines().get(1);
        assertThat(fri.date()).isEqualTo(FRI);
        assertThat(fri.totalGross()).isEqualByComparingTo("2000.00");
        assertThat(fri.rule().daysOfWeek()).isEqualTo("FRI,SAT");
        assertThat(fri.taxPercent()).isEqualByComparingTo("25");
        // same price on both days: merged into one line, 60 persons per day
        CatalogDtos.QuoteLine coffeeLine = quote.lines().get(2);
        assertThat(coffeeLine.days()).isEqualTo(2);
        assertThat(coffeeLine.quantity()).isEqualByComparingTo("60");
        assertThat(coffeeLine.totalGross()).isEqualByComparingTo("780.00");
        assertThat(quote.totalGross()).isEqualByComparingTo("4580.00");
        // 3800 / 1.25 + 780 / 1.13
        assertThat(quote.totalNet()).isEqualByComparingTo("3730.27");
        assertThat(quote.hasItemsOnRequest()).isFalse();
    }

    @Test
    void halfDayUsesHalfDayUomAndExplicitQuantity() {
        CatalogDtos.QuoteResponse quote = quotes.quote(mice, new CatalogDtos.QuoteRequest(THU, null, 20,
                CatalogDtos.DayFormat.HALF_DAY, List.of(new CatalogDtos.QuoteItem(41L, null), new CatalogDtos.QuoteItem(42L, new BigDecimal("40")))));
        assertThat(quote.lines()).extracting(CatalogDtos.QuoteLine::uom).containsExactly("HALF_DAY", "PAX");
        assertThat(quote.lines().get(1).totalGross()).isEqualByComparingTo("260.00");
    }

    @Test
    void itemWithoutPriceIsOnRequestAndUnitIsOneOff() {
        hidden.setPublished(true);
        CatalogDtos.QuoteResponse quote = quotes.quote(mice, new CatalogDtos.QuoteRequest(THU, FRI, 10, null,
                List.of(new CatalogDtos.QuoteItem(43L, null))));
        assertThat(quote.lines()).singleElement().satisfies(l -> {
            assertThat(l.unitPriceGross()).isNull();
            assertThat(l.totalGross()).isNull();
            assertThat(l.uom()).isEqualTo("UNIT");
            assertThat(l.days()).isEqualTo(1);
            assertThat(l.quantity()).isEqualByComparingTo("1");
        });
        assertThat(quote.hasItemsOnRequest()).isTrue();
        assertThat(quote.totalGross()).isEqualByComparingTo("0.00");
    }

    @Test
    void quoteRejectsUnpublishedOutOfSeasonUnknownItemsAndClosedDates() {
        assertThatThrownBy(() -> quotes.quote(mice, new CatalogDtos.QuoteRequest(THU, null, 10, null,
                List.of(new CatalogDtos.QuoteItem(43L, null)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not published");
        assertThatThrownBy(() -> quotes.quote(mice, new CatalogDtos.QuoteRequest(THU, null, 10, null,
                List.of(new CatalogDtos.QuoteItem(44L, null)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not offered on 2027-05-13");
        assertThatThrownBy(() -> quotes.quote(mice, new CatalogDtos.QuoteRequest(THU, null, 10, null,
                List.of(new CatalogDtos.QuoteItem(999L, null)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("items[0]: item 999 is not in catalog MICE");
        assertThatThrownBy(() -> quotes.quote(mice, new CatalogDtos.QuoteRequest(LocalDate.of(2027, 6, 20), null, 10, null,
                List.of(new CatalogDtos.QuoteItem(41L, null)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("closed season");
        assertThatThrownBy(() -> quotes.quote(mice, new CatalogDtos.QuoteRequest(THU, null, null, null,
                List.of(new CatalogDtos.QuoteItem(42L, null)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attendees");
    }
}
