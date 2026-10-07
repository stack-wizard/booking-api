package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.booking.BookingUom;
import com.stackwizard.booking_api.dto.ProductPriceDtos;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.PriceProfileDate;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.PriceProfileDateRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.security.AuthUserAccessor;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmRole;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Prices of one product across price profiles and their validity windows, as shown on the product screen.
 * Reads are open to anyone who can see products; writes need a back-office role or revenue manager.
 */
@Service
public class ProductPriceService {
    private static final BigDecimal MIN_PERCENT = new BigDecimal("-90");
    private static final BigDecimal MAX_PERCENT = new BigDecimal("500");
    private static final Set<CrmRole> SALES_ROLES = Set.of(CrmRole.SALES_REP, CrmRole.SALES_MANAGER, CrmRole.EVENT_COORDINATOR);

    private final PriceListEntryRepository priceRepo;
    private final PriceProfileDateRepository periodRepo;
    private final ProductRepository productRepo;
    private final AuthUserAccessor authUserAccessor;
    private final CrmAccessContext accessContext;

    public ProductPriceService(PriceListEntryRepository priceRepo,
                               PriceProfileDateRepository periodRepo,
                               ProductRepository productRepo,
                               AuthUserAccessor authUserAccessor,
                               CrmAccessContext accessContext) {
        this.priceRepo = priceRepo;
        this.periodRepo = periodRepo;
        this.productRepo = productRepo;
        this.authUserAccessor = authUserAccessor;
        this.accessContext = accessContext;
    }

    @Transactional(readOnly = true)
    public List<ProductPriceDtos.Period> periods() {
        return periodRepo.findForTenant(TenantResolver.requireTenantId()).stream().map(ProductPriceService::toPeriod).toList();
    }

    @Transactional(readOnly = true)
    public List<ProductPriceDtos.PriceRow> prices(Long productId) {
        Long tenantId = TenantResolver.requireTenantId();
        requireProduct(productId, tenantId);
        return priceRepo.findForProduct(productId, tenantId).stream().map(ProductPriceService::toRow).toList();
    }

    @Transactional
    public List<ProductPriceDtos.PriceRow> save(Long productId, ProductPriceDtos.SaveRequest request) {
        requireWrite();
        Long tenantId = TenantResolver.requireTenantId();
        Product product = requireProduct(productId, tenantId);
        Map<Long, PriceListEntry> existing = new HashMap<>();
        for (PriceListEntry e : priceRepo.findForProduct(productId, tenantId)) {
            existing.put(e.getId(), e);
        }
        Map<Long, PriceProfileDate> periods = new HashMap<>();
        for (PriceProfileDate d : periodRepo.findForTenant(tenantId)) {
            periods.put(d.getId(), d);
        }

        if (request != null && request.deleteIds() != null) {
            for (Long id : request.deleteIds()) {
                PriceListEntry entry = existing.remove(id);
                if (entry == null) {
                    throw new IllegalArgumentException("Price not found for this product: " + id);
                }
                priceRepo.delete(entry);
            }
        }
        List<PriceListEntry> touched = new ArrayList<>();
        if (request != null && request.upserts() != null) {
            for (ProductPriceDtos.PriceInput input : request.upserts()) {
                PriceListEntry entry;
                if (input.id() != null) {
                    entry = existing.get(input.id());
                    if (entry == null) {
                        throw new IllegalArgumentException("Price not found for this product: " + input.id());
                    }
                } else {
                    entry = PriceListEntry.builder().productId(productId).build();
                }
                apply(product, periods, entry, input);
                touched.add(entry);
                if (entry.getId() != null) {
                    existing.put(entry.getId(), entry);
                }
            }
        }
        List<PriceListEntry> all = new ArrayList<>(existing.values());
        touched.stream().filter(e -> e.getId() == null).forEach(all::add);
        requireUnique(all);
        priceRepo.saveAll(touched);
        priceRepo.flush();
        return prices(productId);
    }

    @Transactional
    public List<ProductPriceDtos.PriceRow> adjust(Long productId, ProductPriceDtos.AdjustRequest request) {
        requireWrite();
        Long tenantId = TenantResolver.requireTenantId();
        requireProduct(productId, tenantId);
        if (request == null) {
            throw new IllegalArgumentException("percent is required");
        }
        BigDecimal factor = factor(request.percent());
        Set<Long> periodIds = request.priceProfileDateIds() == null || request.priceProfileDateIds().isEmpty()
                ? null : new HashSet<>(request.priceProfileDateIds());
        String uom = request.uom() == null || request.uom().isBlank() ? null : BookingUom.normalize(request.uom());
        List<PriceListEntry> changed = new ArrayList<>();
        for (PriceListEntry e : priceRepo.findForProduct(productId, tenantId)) {
            if (periodIds != null && !periodIds.contains(e.getPriceProfileDate().getId())) {
                continue;
            }
            if (uom != null && !uom.equalsIgnoreCase(e.getUom())) {
                continue;
            }
            e.setPrice(round(e.getPrice().multiply(factor), request.roundTo()));
            changed.add(e);
        }
        if (changed.isEmpty()) {
            throw new IllegalArgumentException("No prices match the selection");
        }
        priceRepo.saveAll(changed);
        return prices(productId);
    }

    @Transactional
    public List<ProductPriceDtos.PriceRow> copy(Long productId, ProductPriceDtos.CopyRequest request) {
        requireWrite();
        Long tenantId = TenantResolver.requireTenantId();
        requireProduct(productId, tenantId);
        if (request == null || request.fromPeriodId() == null || request.toPeriodId() == null) {
            throw new IllegalArgumentException("fromPeriodId and toPeriodId are required");
        }
        if (request.fromPeriodId().equals(request.toPeriodId())) {
            throw new IllegalArgumentException("Source and target period must differ");
        }
        PriceProfileDate target = periodRepo.findForTenant(tenantId).stream()
                .filter(d -> d.getId().equals(request.toPeriodId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Price period not found: " + request.toPeriodId()));
        BigDecimal factor = request.percent() == null ? BigDecimal.ONE : factor(request.percent());
        List<PriceListEntry> current = priceRepo.findForProduct(productId, tenantId);
        List<PriceListEntry> source = current.stream()
                .filter(e -> e.getPriceProfileDate().getId().equals(request.fromPeriodId()))
                .toList();
        if (source.isEmpty()) {
            throw new IllegalArgumentException("The source period has no prices for this product");
        }
        List<PriceListEntry> copies = new ArrayList<>();
        for (PriceListEntry e : source) {
            boolean exists = current.stream().anyMatch(c -> c.getPriceProfileDate().getId().equals(target.getId()) && sameSlot(c, e));
            if (exists) {
                continue;
            }
            copies.add(PriceListEntry.builder()
                    .productId(productId)
                    .uom(e.getUom())
                    .price(round(e.getPrice().multiply(factor), request.roundTo()))
                    .startTime(e.getStartTime())
                    .endTime(e.getEndTime())
                    .priceProfile(target.getPriceProfile())
                    .priceProfileDate(target)
                    .build());
        }
        if (copies.isEmpty()) {
            throw new IllegalStateException("The target period already has all these prices");
        }
        priceRepo.saveAll(copies);
        return prices(productId);
    }

    private void apply(Product product, Map<Long, PriceProfileDate> periods, PriceListEntry entry, ProductPriceDtos.PriceInput input) {
        PriceProfileDate period = input.priceProfileDateId() == null ? null : periods.get(input.priceProfileDateId());
        if (period == null) {
            throw new IllegalArgumentException("priceProfileDateId must be a price period of this tenant");
        }
        String uom = BookingUom.normalize(input.uom());
        if (!allowedUoms(product).contains(uom)) {
            throw new IllegalArgumentException("UOM " + uom + " is not offered by " + product.getName()
                    + "; add it to the product's UOMs first");
        }
        if (input.price() == null || input.price().signum() < 0) {
            throw new IllegalArgumentException("price must be 0 or more");
        }
        LocalTime start = input.startTime();
        LocalTime end = input.endTime();
        if ((start == null) != (end == null)) {
            throw new IllegalArgumentException("Set both start and end time, or neither");
        }
        if (start != null && !start.isBefore(end)) {
            throw new IllegalArgumentException("Start time must be before end time");
        }
        entry.setUom(uom);
        entry.setPrice(input.price().setScale(2, RoundingMode.HALF_UP));
        entry.setStartTime(start);
        entry.setEndTime(end);
        entry.setPriceProfileDate(period);
        entry.setPriceProfile(period.getPriceProfile());
    }

    private static void requireUnique(List<PriceListEntry> entries) {
        Set<String> seen = new HashSet<>();
        for (PriceListEntry e : entries) {
            String key = e.getPriceProfileDate().getId() + "|" + e.getUom() + "|" + e.getStartTime() + "|" + e.getEndTime();
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Duplicate price for " + e.getUom() + " in "
                        + e.getPriceProfileDate().getDateFrom() + " – " + e.getPriceProfileDate().getDateTo());
            }
        }
    }

    private static boolean sameSlot(PriceListEntry a, PriceListEntry b) {
        return a.getUom().equalsIgnoreCase(b.getUom())
                && Objects.equals(a.getStartTime(), b.getStartTime())
                && Objects.equals(a.getEndTime(), b.getEndTime());
    }

    private static Set<String> allowedUoms(Product product) {
        Set<String> uoms = new HashSet<>();
        if (product.getDefaultUom() != null) {
            uoms.add(product.getDefaultUom().trim().toUpperCase(Locale.ROOT));
        }
        if (product.getExtraUoms() != null) {
            product.getExtraUoms().forEach(u -> uoms.add(u.trim().toUpperCase(Locale.ROOT)));
        }
        return uoms;
    }

    private static BigDecimal factor(BigDecimal percent) {
        if (percent == null) {
            throw new IllegalArgumentException("percent is required");
        }
        if (percent.compareTo(MIN_PERCENT) < 0 || percent.compareTo(MAX_PERCENT) > 0) {
            throw new IllegalArgumentException("percent must be between -90 and 500");
        }
        return BigDecimal.ONE.add(percent.movePointLeft(2));
    }

    static BigDecimal round(BigDecimal value, BigDecimal roundTo) {
        if (roundTo == null || roundTo.signum() <= 0) {
            return value.setScale(2, RoundingMode.HALF_UP);
        }
        return value.divide(roundTo, 0, RoundingMode.HALF_UP).multiply(roundTo).setScale(2, RoundingMode.HALF_UP);
    }

    private Product requireProduct(Long productId, Long tenantId) {
        return productRepo.findByIdAndTenantId(productId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    /** Admins and revenue managers; plain staff unless they only work in sales. */
    private void requireWrite() {
        AppUser user = authUserAccessor.requireAppUser();
        Set<CrmRole> crmRoles = accessContext.crmRoles();
        boolean admin = user.getRole() == AppUser.Role.SUPER_ADMIN || user.getRole() == AppUser.Role.ADMIN;
        boolean revenue = crmRoles.contains(CrmRole.REVENUE_MANAGER);
        boolean backOffice = user.getRole() == AppUser.Role.STAFF && crmRoles.stream().noneMatch(SALES_ROLES::contains);
        if (!admin && !revenue && !backOffice) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins and revenue managers can change prices");
        }
    }

    private static ProductPriceDtos.Period toPeriod(PriceProfileDate d) {
        var profile = d.getPriceProfile();
        return new ProductPriceDtos.Period(d.getId(), profile.getId(), profile.getName(), profile.getCurrency(),
                profile.getReservationRequestType(), d.getDateFrom(), d.getDateTo(), d.getDescription());
    }

    private static ProductPriceDtos.PriceRow toRow(PriceListEntry e) {
        return new ProductPriceDtos.PriceRow(e.getId(), toPeriod(e.getPriceProfileDate()), e.getUom(), e.getPrice(),
                e.getStartTime(), e.getEndTime());
    }
}
