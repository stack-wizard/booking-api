package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ProductComponent;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.ProductComponentRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A package is a product with components. The package price is per person; components either sum up
 * (SUM) or split the package price by percent (SPLIT_PERCENT) or by fixed amounts (SPLIT_FIXED).
 */
@Service
public class PackageService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ProductRepository productRepo;
    private final ProductComponentRepository componentRepo;
    private final PriceListEntryResolver priceResolver;

    public PackageService(ProductRepository productRepo,
                          ProductComponentRepository componentRepo,
                          PriceListEntryResolver priceResolver) {
        this.productRepo = productRepo;
        this.componentRepo = componentRepo;
        this.priceResolver = priceResolver;
    }

    public List<ProductComponent> components(Long packageProductId) {
        Long tenantId = TenantResolver.requireTenantId();
        requireProduct(tenantId, packageProductId);
        return componentRepo.findByTenantIdAndPackageProductIdOrderByDisplayOrderAscIdAsc(tenantId, packageProductId);
    }

    /**
     * Sets the pricing mode and replaces all components. A null mode turns the product back into a plain product.
     */
    @Transactional
    public PackageDefinition define(Long packageProductId, Product.PackagePricing pricing, List<ProductComponent> components) {
        Long tenantId = TenantResolver.requireTenantId();
        Product pkg = requireProduct(tenantId, packageProductId);
        List<ProductComponent> incoming = components == null ? List.of() : components;
        if (pricing == null && !incoming.isEmpty()) {
            throw new IllegalArgumentException("packagePricing is required when components are given");
        }
        if (pricing != null && incoming.isEmpty()) {
            throw new IllegalArgumentException("A package needs at least one component");
        }
        List<ProductComponent> rows = new ArrayList<>();
        int order = 0;
        for (ProductComponent component : incoming) {
            rows.add(normalizeComponent(tenantId, pkg, pricing, component, order++));
        }
        validateSplit(pricing, rows);
        pkg.setPackagePricing(pricing);
        productRepo.save(pkg);
        componentRepo.deleteByTenantIdAndPackageProductId(tenantId, pkg.getId());
        componentRepo.flush();
        List<ProductComponent> saved = componentRepo.saveAll(rows);
        return new PackageDefinition(pricing, saved);
    }

    /** Quote group of the product; DISCOUNT is reserved for manual quote lines. */
    @Transactional
    public Product setSalesGroup(Long productId, com.stackwizard.booking_api.model.SalesQuoteLine.Group group) {
        if (group == com.stackwizard.booking_api.model.SalesQuoteLine.Group.DISCOUNT) {
            throw new IllegalArgumentException("DISCOUNT is not a product sales group");
        }
        Product product = requireProduct(TenantResolver.requireTenantId(), productId);
        product.setSalesGroup(group);
        return productRepo.save(product);
    }

    @Transactional(readOnly = true)
    public PackageQuote quote(Long packageProductId, LocalDate date, Integer pax, String currency) {
        Long tenantId = TenantResolver.requireTenantId();
        return quote(tenantId, packageProductId, date, pax, currency);
    }

    PackageQuote quote(Long tenantId, Long packageProductId, LocalDate date, Integer pax, String currency) {
        Product pkg = requireProduct(tenantId, packageProductId);
        if (pkg.getPackagePricing() == null) {
            throw new IllegalArgumentException(pkg.getName() + " is not a package");
        }
        if (date == null) {
            throw new IllegalArgumentException("date is required");
        }
        int persons = pax == null ? 1 : pax;
        if (persons <= 0) {
            throw new IllegalArgumentException("pax must be > 0");
        }
        List<ProductComponent> components = componentRepo.findByTenantIdAndPackageProductIdOrderByDisplayOrderAscIdAsc(
                tenantId, pkg.getId());
        Map<Long, Product> products = productRepo.findAllById(
                        components.stream().map(ProductComponent::getComponentProductId).distinct().toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        BigDecimal pricePerPerson = pkg.getPackagePricing() == Product.PackagePricing.SUM
                ? null
                : catalogPrice(pkg, currency, tenantId, date)
                .orElseThrow(() -> new IllegalArgumentException("No package price for " + pkg.getName() + " on " + date));

        List<ComponentQuote> lines = new ArrayList<>();
        BigDecimal includedPerPerson = BigDecimal.ZERO;
        BigDecimal includedTotal = BigDecimal.ZERO;
        BigDecimal extrasTotal = BigDecimal.ZERO;
        for (ProductComponent component : components) {
            Product product = products.get(component.getComponentProductId());
            int qty = component.getQtyBasis() == ProductComponent.QtyBasis.PER_PAX ? component.getQty() * persons : component.getQty();
            BigDecimal amount;
            BigDecimal perPerson = null;
            if (!component.getIncluded() || pkg.getPackagePricing() == Product.PackagePricing.SUM) {
                BigDecimal unit = catalogPrice(product, currency, tenantId, date)
                        .orElseThrow(() -> new IllegalArgumentException("No price for component " + product.getName() + " on " + date));
                amount = unit.multiply(BigDecimal.valueOf(qty));
            } else {
                perPerson = pkg.getPackagePricing() == Product.PackagePricing.SPLIT_PERCENT
                        ? pricePerPerson.multiply(component.getSharePercent()).divide(HUNDRED, 8, RoundingMode.HALF_UP)
                        : component.getFixedAmount();
                amount = perPerson.multiply(BigDecimal.valueOf(persons));
            }
            amount = money(amount);
            BigDecimal unitPrice = money(amount.divide(BigDecimal.valueOf(qty), 8, RoundingMode.HALF_UP));
            if (component.getIncluded()) {
                includedTotal = includedTotal.add(amount);
                includedPerPerson = includedPerPerson.add(perPerson != null ? perPerson
                        : amount.divide(BigDecimal.valueOf(persons), 8, RoundingMode.HALF_UP));
            } else {
                extrasTotal = extrasTotal.add(amount);
            }
            lines.add(new ComponentQuote(component, product.getName(), qty, unitPrice, amount,
                    product.getTax1Percent(), product.getTax2Percent()));
        }
        BigDecimal packageTotal = pricePerPerson != null ? money(pricePerPerson.multiply(BigDecimal.valueOf(persons))) : money(includedTotal);
        BigDecimal difference = pricePerPerson != null ? money(includedPerPerson.subtract(pricePerPerson)) : BigDecimal.ZERO.setScale(2);
        return new PackageQuote(pkg.getId(), pkg.getName(), pkg.getPackagePricing(), persons,
                pricePerPerson != null ? money(pricePerPerson) : money(includedPerPerson),
                packageTotal, money(includedTotal), money(extrasTotal), difference, lines);
    }

    private ProductComponent normalizeComponent(Long tenantId, Product pkg, Product.PackagePricing pricing,
                                                ProductComponent component, int order) {
        if (component == null || component.getComponentProductId() == null) {
            throw new IllegalArgumentException("componentProductId is required");
        }
        if (component.getComponentProductId().equals(pkg.getId())) {
            throw new IllegalArgumentException("A package cannot contain itself");
        }
        Product product = requireProduct(tenantId, component.getComponentProductId());
        if (product.getPackagePricing() != null
                || componentRepo.existsByTenantIdAndPackageProductId(tenantId, product.getId())) {
            throw new IllegalArgumentException(product.getName() + " is a package; nested packages are not supported");
        }
        int qty = component.getQty() == null ? 1 : component.getQty();
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be > 0");
        }
        boolean included = component.getIncluded() == null || component.getIncluded();
        BigDecimal share = component.getSharePercent();
        BigDecimal fixed = component.getFixedAmount();
        if (!included || pricing == Product.PackagePricing.SUM) {
            share = null;
            fixed = null;
        } else if (pricing == Product.PackagePricing.SPLIT_PERCENT) {
            if (share == null || share.signum() < 0 || share.compareTo(HUNDRED) > 0) {
                throw new IllegalArgumentException("sharePercent 0-100 is required for " + product.getName());
            }
            fixed = null;
        } else if (pricing == Product.PackagePricing.SPLIT_FIXED) {
            if (fixed == null || fixed.signum() < 0) {
                throw new IllegalArgumentException("fixedAmount >= 0 is required for " + product.getName());
            }
            share = null;
        }
        if (component.getDurationMinutes() != null && component.getDurationMinutes() <= 0) {
            throw new IllegalArgumentException("durationMinutes must be > 0");
        }
        if (component.getStartOffsetMinutes() != null && component.getStartOffsetMinutes() < 0) {
            throw new IllegalArgumentException("startOffsetMinutes must be >= 0");
        }
        return ProductComponent.builder()
                .tenantId(tenantId)
                .packageProductId(pkg.getId())
                .componentProductId(product.getId())
                .qty(qty)
                .qtyBasis(component.getQtyBasis() != null ? component.getQtyBasis() : ProductComponent.QtyBasis.PER_PAX)
                .included(included)
                .sharePercent(share)
                .fixedAmount(fixed)
                .functionType(component.getFunctionType())
                .startOffsetMinutes(component.getStartOffsetMinutes())
                .durationMinutes(component.getDurationMinutes())
                .displayOrder(component.getDisplayOrder() != null ? component.getDisplayOrder() : order)
                .build();
    }

    private static void validateSplit(Product.PackagePricing pricing, List<ProductComponent> rows) {
        if (pricing != Product.PackagePricing.SPLIT_PERCENT) {
            return;
        }
        BigDecimal sum = rows.stream()
                .filter(ProductComponent::getIncluded)
                .map(ProductComponent::getSharePercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(HUNDRED) != 0) {
            throw new IllegalArgumentException("Included component shares must add up to 100%, got " + sum.stripTrailingZeros().toPlainString() + "%");
        }
    }

    private java.util.Optional<BigDecimal> catalogPrice(Product product, String currency, Long tenantId, LocalDate date) {
        List<PriceListEntry> entries = priceResolver.findEffectiveForProductUomOnDate(
                product.getId(), product.getDefaultUom(), currency == null ? "EUR" : currency, tenantId, date,
                ReservationRequest.Type.INTERNAL);
        return entries.stream().map(PriceListEntry::getPrice).filter(p -> p != null).findFirst();
    }

    private Product requireProduct(Long tenantId, Long productId) {
        if (productId == null) {
            throw new IllegalArgumentException("productId is required");
        }
        return productRepo.findByIdAndTenantId(productId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public record PackageDefinition(Product.PackagePricing packagePricing, List<ProductComponent> components) {
    }

    public record ComponentQuote(ProductComponent component,
                                 String productName,
                                 int qty,
                                 BigDecimal unitPrice,
                                 BigDecimal amount,
                                 BigDecimal tax1Percent,
                                 BigDecimal tax2Percent) {
    }

    /**
     * difference = included per-person sum minus package price per person; must be 0 for SPLIT_FIXED to be applied.
     */
    public record PackageQuote(Long packageProductId,
                               String name,
                               Product.PackagePricing packagePricing,
                               int pax,
                               BigDecimal pricePerPerson,
                               BigDecimal packageTotal,
                               BigDecimal includedTotal,
                               BigDecimal extrasTotal,
                               BigDecimal difference,
                               List<ComponentQuote> components) {
    }
}
