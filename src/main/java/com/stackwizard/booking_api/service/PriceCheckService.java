package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.booking.BookingUom;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.security.TenantContext;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.stream.Collectors;

/** Admin "price check": which price wins for a product on a date and why the others lost. */
@Service
public class PriceCheckService {
    private final PriceListEntryResolver resolver;
    private final ProductRepository productRepo;
    private final PriceListEntryRepository priceRepo;
    private final PricingAccess access;

    public PriceCheckService(PriceListEntryResolver resolver, ProductRepository productRepo,
                             PriceListEntryRepository priceRepo, PricingAccess access) {
        this.resolver = resolver;
        this.productRepo = productRepo;
        this.priceRepo = priceRepo;
        this.access = access;
    }

    /**
     * Prices of the selected hotel (X-Property-Id) win over chain prices; without a hotel only chain prices count.
     * {@code uom} defaults to the product's default UOM, {@code currency} to the currency most of its prices use.
     */
    @Transactional(readOnly = true)
    public PriceListEntryResolver.PriceCheck check(Long productId, LocalDate date, String uom, LocalTime time,
                                                   ReservationRequest.Type requestType, String currency) {
        access.requireRead();
        if (date == null) {
            throw new IllegalArgumentException("date is required");
        }
        Long org = TenantResolver.requireOrgTenantId();
        Product product = productRepo.findByIdAndTenantId(productId, org)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        String u = uom == null || uom.isBlank() ? product.getDefaultUom() : BookingUom.normalize(uom);
        String cur = currency != null && !currency.isBlank() ? currency.trim().toUpperCase() : defaultCurrency(productId, org);
        Long property = TenantContext.getPropertyTenantId();
        return resolver.explain(productId, u, cur, property != null ? property : org, date, time,
                requestType != null ? requestType : ReservationRequest.Type.EXTERNAL);
    }

    private String defaultCurrency(Long productId, Long org) {
        Map<String, Long> counts = priceRepo.findForProduct(productId, org).stream()
                .map(PriceListEntry::getPriceProfile)
                .collect(Collectors.groupingBy(p -> p.getCurrency().toUpperCase(), Collectors.counting()));
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry::getKey, java.util.Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElse("EUR");
    }
}
