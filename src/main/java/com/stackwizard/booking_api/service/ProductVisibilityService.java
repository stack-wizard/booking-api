package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ProductVisibilityDtos;
import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ProductProperty;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.repository.ProductPropertyRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which hotels of the chain offer a product. The product itself (name, UOM, tax) stays on the chain;
 * only the visibility is decided per hotel.
 */
@Service
public class ProductVisibilityService {
    private final ProductRepository productRepo;
    private final ProductPropertyRepository propertyRepo;
    private final PlatformTenantMappingRepository mappingRepo;

    public ProductVisibilityService(ProductRepository productRepo,
                                    ProductPropertyRepository propertyRepo,
                                    PlatformTenantMappingRepository mappingRepo) {
        this.productRepo = productRepo;
        this.propertyRepo = propertyRepo;
        this.mappingRepo = mappingRepo;
    }

    @Transactional(readOnly = true)
    public ProductVisibilityDtos.Response get(Long productId) {
        Long orgTenantId = TenantResolver.requireOrgTenantId();
        Product product = requireProduct(orgTenantId, productId);
        return toResponse(product, hotels(orgTenantId));
    }

    @Transactional
    public ProductVisibilityDtos.Response update(Long productId, ProductVisibilityDtos.Request request) {
        if (request == null || request.propertyVisibility() == null) {
            throw new IllegalArgumentException("propertyVisibility is required");
        }
        Long orgTenantId = TenantResolver.requireOrgTenantId();
        Product product = requireProduct(orgTenantId, productId);
        List<PlatformTenantMapping> hotels = hotels(orgTenantId);
        Set<Long> hotelIds = hotels.stream().map(PlatformTenantMapping::getTenantId).collect(Collectors.toSet());

        List<ProductVisibilityDtos.PropertySwitch> switches =
                request.properties() == null ? List.of() : request.properties();
        Map<Long, Boolean> wanted = new HashMap<>();
        for (ProductVisibilityDtos.PropertySwitch s : switches) {
            if (s == null || s.tenantId() == null || s.visible() == null) {
                throw new IllegalArgumentException("every property switch needs tenantId and visible");
            }
            if (!hotelIds.contains(s.tenantId())) {
                throw new IllegalArgumentException("Hotel " + s.tenantId() + " does not belong to this organization");
            }
            wanted.put(s.tenantId(), s.visible());
        }

        product.setPropertyVisibility(request.propertyVisibility());
        productRepo.save(product);

        propertyRepo.deleteByProductIdAndTenantIdIn(product.getId(), hotelIds);
        propertyRepo.flush();
        List<ProductProperty> rows = new ArrayList<>();
        for (Map.Entry<Long, Boolean> e : wanted.entrySet()) {
            rows.add(ProductProperty.builder()
                    .tenantId(e.getKey())
                    .productId(product.getId())
                    .visible(e.getValue())
                    .build());
        }
        propertyRepo.saveAll(rows);
        return toResponse(product, hotels);
    }

    /** Whether the hotel offers the chain product. A hotel without a switch follows the product's mode. */
    @Transactional(readOnly = true)
    public boolean isVisible(Product product, Long propertyTenantId) {
        if (product == null || propertyTenantId == null) {
            return false;
        }
        List<ProductProperty> rows = propertyRepo.findByProductIdAndTenantIdIn(product.getId(), List.of(propertyTenantId));
        Boolean explicit = rows.isEmpty() ? null : rows.get(0).getVisible();
        return effective(product, explicit);
    }

    /** @throws IllegalArgumentException when the hotel does not offer the product */
    public void requireVisible(Product product, Long propertyTenantId) {
        if (!isVisible(product, propertyTenantId)) {
            throw new IllegalArgumentException("Product " + product.getName() + " is not offered in this hotel");
        }
    }

    private ProductVisibilityDtos.Response toResponse(Product product, List<PlatformTenantMapping> hotels) {
        Set<Long> hotelIds = hotels.stream().map(PlatformTenantMapping::getTenantId).collect(Collectors.toSet());
        Map<Long, Boolean> explicit = new HashMap<>();
        if (!hotelIds.isEmpty()) {
            for (ProductProperty row : propertyRepo.findByProductIdAndTenantIdIn(product.getId(), hotelIds)) {
                explicit.put(row.getTenantId(), row.getVisible());
            }
        }
        List<ProductVisibilityDtos.PropertyVisibility> properties = hotels.stream()
                .map(h -> new ProductVisibilityDtos.PropertyVisibility(
                        h.getTenantId(), h.getPlatformTenantId(), h.getHotelCode(), h.getName(),
                        effective(product, explicit.get(h.getTenantId())), explicit.containsKey(h.getTenantId())))
                .toList();
        return new ProductVisibilityDtos.Response(product.getPropertyVisibility(), properties);
    }

    private static boolean effective(Product product, Boolean explicit) {
        if (product.getPropertyVisibility() == Product.PropertyVisibility.SELECTED) {
            return Boolean.TRUE.equals(explicit);
        }
        return !Boolean.FALSE.equals(explicit);
    }

    private List<PlatformTenantMapping> hotels(Long orgTenantId) {
        return mappingRepo.findByParentTenantIdOrderByHotelCodeAscTenantIdAsc(orgTenantId);
    }

    private Product requireProduct(Long orgTenantId, Long productId) {
        return productRepo.findByIdAndTenantId(productId, orgTenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }
}
