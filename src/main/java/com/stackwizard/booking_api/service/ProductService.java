package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ProductSelectionDto;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Optional;

@Service
public class ProductService {
    private static final Set<String> SUPPORTED_PRODUCT_TYPES = Set.of(
            "SEALABLE_PRODUCT",
            "DEPOSIT",
            "DEPOSIT_STORNO"
    );

    private final ProductRepository repo;
    private final PriceListEntryRepository priceListRepo;

    public ProductService(ProductRepository repo,
                          PriceListEntryRepository priceListRepo) {
        this.repo = repo;
        this.priceListRepo = priceListRepo;
    }

    public List<Product> findAll() { return repo.findAll(); }
    public Optional<Product> findById(Long id) { return repo.findById(id); }
    public Product save(Product product) {
        if (product.getTax1Percent() == null) {
            product.setTax1Percent(BigDecimal.ZERO);
        }
        if (product.getTax2Percent() == null) {
            product.setTax2Percent(BigDecimal.ZERO);
        }
        if (!StringUtils.hasText(product.getProductType())) {
            product.setProductType("SEALABLE_PRODUCT");
        } else {
            product.setProductType(product.getProductType().trim().toUpperCase(Locale.ROOT));
        }
        if (!SUPPORTED_PRODUCT_TYPES.contains(product.getProductType())) {
            throw new IllegalArgumentException("productType must be SEALABLE_PRODUCT, DEPOSIT, or DEPOSIT_STORNO");
        }
        return repo.save(product);
    }
    public void deleteById(Long id) { repo.deleteById(id); }

    public List<Product> autocomplete(Long tenantId, String query, int limit) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        int normalizedLimit = Math.min(Math.max(limit, 1), 50);
        PageRequest page = PageRequest.of(0, normalizedLimit);
        if (!StringUtils.hasText(query)) {
            return repo.findByTenantIdOrderByNameAsc(tenantId, page).getContent();
        }
        return repo.findByTenantIdAndNameContainingIgnoreCaseOrderByNameAsc(
                tenantId,
                query.trim(),
                page
        ).getContent();
    }

    public ProductSelectionDto selection(Long tenantId,
                                         Long productId,
                                         String currency,
                                         String uom,
                                         LocalDate date) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId is required");
        }

        Product product = repo.findByIdAndTenantId(productId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));

        String effectiveUom = StringUtils.hasText(uom) ? uom.trim() : product.getDefaultUom();
        String effectiveCurrency = StringUtils.hasText(currency) ? currency.trim().toUpperCase(Locale.ROOT) : "EUR";
        LocalDate effectiveDate = date != null ? date : LocalDate.now();

        List<PriceListEntry> entries = priceListRepo.findForProductUomOnDate(
                product.getId(),
                effectiveUom,
                effectiveCurrency,
                tenantId,
                effectiveDate
        );
        BigDecimal unitPriceGross = entries.isEmpty() ? null : entries.get(0).getPrice();

        return ProductSelectionDto.builder()
                .productId(product.getId())
                .productName(product.getName())
                .description(product.getName())
                .tax1Percent(product.getTax1Percent())
                .tax2Percent(product.getTax2Percent())
                .unitPriceGross(unitPriceGross)
                .currency(effectiveCurrency)
                .uom(effectiveUom)
                .priceDate(effectiveDate)
                .build();
    }
}
