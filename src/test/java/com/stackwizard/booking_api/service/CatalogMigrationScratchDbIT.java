package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.CatalogDtos;
import com.stackwizard.booking_api.dto.ResourceMapPeriodsResponse;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration check on a scratch copy of a real database (never the dev DB itself). Run with
 * {@code CATALOG_SCRATCH_DB_URL=jdbc:postgresql://localhost:5432/<scratch>} and, for the map comparison,
 * {@code CATALOG_SOURCE_DB_URL} = the untouched source database (read only).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "CATALOG_SCRATCH_DB_URL", matches = ".+")
class CatalogMigrationScratchDbIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CATALOG_SCRATCH_DB_URL"));
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PriceListEntryRepository priceRepo;
    @Autowired PriceListEntryResolver resolver;
    @Autowired ResourceMapService mapService;
    @Autowired CatalogWebService webService;
    @Autowired PackageCatalogService packageCatalogService;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void printsWhatTheMigrationCreated() {
        System.out.println("[scratch] calendars " + jdbc.queryForList("select id, tenant_id, property_tenant_id, name from season_calendar"));
        System.out.println("[scratch] seasons " + jdbc.queryForList("select season_calendar_id, code, name, date_from, date_to, status, note from season order by id"));
        System.out.println("[scratch] catalogs " + jdbc.queryForList("select id, tenant_id, property_tenant_id, code, sale_mode, season_calendar_id, request_type, currency from sales_catalog order by id"));
        System.out.println("[scratch] items " + jdbc.queryForList("select catalog_id, item_kind, item_group, published, count(*) n from catalog_item group by 1,2,3,4 order by 1,2,3"));
        System.out.println("[scratch] catalog maps " + jdbc.queryForList("select * from catalog_map order by 1,2"));
        assertThat(jdbc.queryForObject("select count(*) from price_profile_date where period_kind <> 'SEASON' or season_id is not null", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from price_list where days_of_week is not null", Long.class)).isZero();
        // every resource on a map of a hotel with a BEACH catalog is a published SPOT item
        assertThat(jdbc.queryForObject("""
                select count(*) from resource_map_resource x join resource_map m on m.id = x.resource_map_id
                where not exists (select 1 from catalog_item i join sales_catalog c on c.id = i.catalog_id
                                  where c.code = 'BEACH' and i.resource_id = x.resource_id and i.published)
                """, Long.class)).isZero();
        // every package listing is a PACKAGE item of MICE with the same publication state
        assertThat(jdbc.queryForObject("""
                select count(*) from product_package_listing l
                where not exists (select 1 from catalog_item i join sales_catalog c on c.id = i.catalog_id
                                  where c.code = 'MICE' and i.product_id = l.product_id and i.item_kind = 'PACKAGE'
                                    and i.published = l.published and i.min_pax is not distinct from l.min_pax)
                """, Long.class)).isZero();
    }

    /** Old rule (level + channel, first in query order) and the new resolver give the same price for all existing prices. */
    @Test
    void resolverGivesSamePricesAsBeforeForAllExistingPrices() {
        List<Map<String, Object>> samples = jdbc.queryForList("""
                select distinct pl.product_id, pl.uom, upper(pp.currency) currency, pp.tenant_id, pp.property_tenant_id,
                       d.date_from, d.date_to
                from price_list pl join price_profile pp on pp.id = pl.price_profile_id
                join price_profile_date d on d.id = pl.price_profile_date_id
                """);
        int checked = 0;
        for (Map<String, Object> s : samples) {
            Long productId = ((Number) s.get("product_id")).longValue();
            String uom = (String) s.get("uom");
            String currency = (String) s.get("currency");
            Long tenant = s.get("property_tenant_id") != null ? ((Number) s.get("property_tenant_id")).longValue()
                    : ((Number) s.get("tenant_id")).longValue();
            LocalDate from = ((Date) s.get("date_from")).toLocalDate();
            LocalDate to = ((Date) s.get("date_to")).toLocalDate();
            List<LocalDate> dates = List.of(from, to, from.plusDays(java.time.temporal.ChronoUnit.DAYS.between(from, to) / 2));
            for (LocalDate date : dates) {
                for (ReservationRequest.Type type : ReservationRequest.Type.values()) {
                    List<String> before = tx.execute(st -> legacy(productId, uom, currency, tenant, date, type));
                    List<String> after = tx.execute(st -> resolver.findEffectiveForProductUomOnDate(productId, uom, currency,
                            tenant, date, type).stream().map(CatalogMigrationScratchDbIT::key).toList());
                    assertThat(after).as(productId + " " + uom + " " + date + " " + type).isEqualTo(before);
                    checked++;
                }
            }
        }
        System.out.println("[scratch] resolver comparisons: " + checked);
        assertThat(checked).isPositive();
    }

    private List<String> legacy(Long productId, String uom, String currency, Long tenant, LocalDate date, ReservationRequest.Type type) {
        List<PriceListEntry> candidates = priceRepo.findCandidatesForProductUomOnDate(productId, uom, currency,
                jdbc.queryForObject("select coalesce((select parent_tenant_id from platform_tenant_mapping where tenant_id = ? and kind = 'PROPERTY'), ?)",
                        Long.class, tenant, tenant), tenant, date, type);
        Map<String, PriceListEntry> best = new java.util.LinkedHashMap<>();
        for (PriceListEntry c : candidates) {
            String k = c.getStartTime() + "|" + c.getEndTime();
            PriceListEntry cur = best.get(k);
            if (cur == null || spec(c, type) < spec(cur, type)) {
                best.put(k, c);
            }
        }
        return best.values().stream().map(CatalogMigrationScratchDbIT::key).toList();
    }

    private static int spec(PriceListEntry e, ReservationRequest.Type type) {
        return (e.getPriceProfile().getPropertyTenantId() != null ? 0 : 2) + (e.getPriceProfile().getReservationRequestType() == type ? 0 : 1);
    }

    private static String key(PriceListEntry e) {
        return e.getId() + ":" + e.getPrice().stripTrailingZeros().toPlainString();
    }

    /** Resource maps are untouched, so /api/resource-maps/periods returns the same ranges as on the source DB. */
    @Test
    void resourceMapPeriodsUnchanged() {
        String source = System.getenv("CATALOG_SOURCE_DB_URL");
        String query = "select id, tenant_id, location_node_id, parent_map_id, name, valid_from, valid_to from resource_map order by id";
        List<Map<String, Object>> after = jdbc.queryForList(query);
        if (source != null && !source.isBlank()) {
            DriverManagerDataSource ds = new DriverManagerDataSource(source, "booking_user", "booking_pass");
            assertThat(new JdbcTemplate(ds).queryForList(query)).isEqualTo(after);
        }
        for (Long tenant : jdbc.queryForList("select distinct tenant_id from resource_map", Long.class)) {
            ResourceMapPeriodsResponse periods = mapService.getMapPeriods(tenant, null, LocalDate.of(2026, 1, 1));
            System.out.println("[scratch] periods tenant " + tenant + ": " + periods.getFirstDate() + " .. " + periods.getLastDate()
                    + " " + periods.getPeriods().stream().map(p -> p.getMapName() + " " + p.getValidFrom() + "-" + p.getValidTo()).toList());
            List<Map<String, Object>> seasons = jdbc.queryForList("""
                    select s.date_from, s.date_to from season s join season_calendar c on c.id = s.season_calendar_id
                    where c.property_tenant_id = ? order by s.date_from""", tenant);
            assertThat(seasons).isNotEmpty();
            assertThat(((Date) seasons.get(0).get("date_from")).toLocalDate()).isEqualTo(periods.getFirstDate());
        }
    }

    /** The web API on real (migrated) data, as the CMS m2m client of tenant 1. */
    @Test
    void webApiOnMigratedData() {
        TenantContext.setTenantId(1L);
        Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), Map.of("token_use", "m2m"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_API"))));

        List<CatalogDtos.CatalogSummary> catalogs = webService.catalogs();
        System.out.println("[scratch] /api/catalogs " + catalogs);
        assertThat(catalogs).extracting(CatalogDtos.CatalogSummary::code).contains("BEACH", "MICE");

        System.out.println("[scratch] BEACH seasons " + webService.seasons("BEACH", null, null));
        LocalDate day = LocalDate.of(2026, 5, 2);
        List<CatalogDtos.ItemView> beach = webService.items("BEACH", day, null, null, null);
        System.out.println("[scratch] BEACH items on " + day + ": " + beach.size() + ", first " + (beach.isEmpty() ? null : beach.get(0)));
        assertThat(beach).isNotEmpty();
        assertThat(beach).allMatch(i -> "SPOT".equals(i.group()));
        List<CatalogDtos.MapView> maps = webService.maps("BEACH", day);
        assertThat(maps).isNotEmpty();
        long selectable = maps.get(0).positions().stream().filter(CatalogDtos.Position::selectable).count();
        System.out.println("[scratch] BEACH map " + maps.get(0).name() + " positions " + maps.get(0).positions().size() + " selectable " + selectable);
        assertThat(webService.items("BEACH", LocalDate.of(2026, 12, 1), null, null, null)).isEmpty();

        CatalogDtos.ItemView priced = beach.stream().filter(i -> !i.prices().isEmpty()).findFirst().orElseThrow();
        CatalogDtos.QuoteResponse quote = webService.quote("BEACH", new CatalogDtos.QuoteRequest(day, day.plusDays(1), 2, null,
                List.of(new CatalogDtos.QuoteItem(priced.itemId(), null))));
        System.out.println("[scratch] BEACH quote " + quote);
        assertThat(quote.lines()).isNotEmpty();

        List<CatalogDtos.ItemView> mice = webService.items("MICE", null, null, null, "PACKAGE");
        System.out.println("[scratch] MICE packages " + mice.stream().map(i -> i.name() + " " + i.pricePerPerson()).toList());
        assertThat(mice).isNotEmpty();
        List<PackageCatalogService.CatalogPackage> legacyPackages = tx.execute(st -> packageCatalogService.catalog(null, null));
        assertThat(legacyPackages).extracting(PackageCatalogService.CatalogPackage::name)
                .containsExactlyElementsOf(new ArrayList<>(mice.stream().map(CatalogDtos.ItemView::name).filter(Objects::nonNull).toList()));
        BigDecimal ppp = legacyPackages.get(0).pricePerPerson();
        System.out.println("[scratch] /api/catalog/packages " + legacyPackages.get(0).name() + " " + ppp);
    }
}
