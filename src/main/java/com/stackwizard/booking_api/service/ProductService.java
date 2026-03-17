package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ProductGalleryImageDto;
import com.stackwizard.booking_api.dto.ProductSelectionDto;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ProductImage;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductService {
    private static final Set<String> SUPPORTED_PRODUCT_TYPES = Set.of(
            "SEALABLE_PRODUCT",
            "DEPOSIT",
            "DEPOSIT_STORNO"
    );

    private final ProductRepository repo;
    private final PriceListEntryRepository priceListRepo;
    private final MediaStorageService mediaStorageService;

    public ProductService(ProductRepository repo,
                          PriceListEntryRepository priceListRepo,
                          MediaStorageService mediaStorageService) {
        this.repo = repo;
        this.priceListRepo = priceListRepo;
        this.mediaStorageService = mediaStorageService;
    }

    @Transactional(readOnly = true)
    public List<Product> findAll() {
        return repo.findAll(Sort.by(
                Sort.Order.asc("tenantId"),
                Sort.Order.asc("displayOrder"),
                Sort.Order.asc("name"),
                Sort.Order.asc("id")
        ));
    }

    @Transactional(readOnly = true)
    public Optional<Product> findById(Long id) { return repo.findById(id); }

    @Transactional
    public Product save(Product product) {
        if (product == null) {
            throw new IllegalArgumentException("product is required");
        }

        Product target = product.getId() == null
                ? new Product()
                : repo.findById(product.getId()).orElseThrow(() -> new IllegalArgumentException("Product not found"));

        copyEditableFields(product, target);
        normalizeAndValidate(target);
        syncImages(target, product.getImages());
        if (product.getImages() != null) {
            return saveWithStagedDefaultImage(target);
        }
        return repo.save(target);
    }

    @Transactional
    public Product uploadImage(Long productId,
                               MultipartFile file,
                               boolean defaultImage,
                               Integer position) {
        Product product = repo.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));

        validateImageFile(file);

        Long tenantId = TenantResolver.requireTenantId(product.getTenantId());
        String imageUrl = mediaStorageService.uploadPublic(
                "products",
                tenantId,
                "product-" + productId + "-image-" + UUID.randomUUID(),
                file
        );

        if (product.getImages() == null) {
            product.setImages(new ArrayList<>());
        }

        if (defaultImage) {
            clearDefaultImage(product.getImages());
        }

        ProductImage image = ProductImage.builder()
                .product(product)
                .imageUrl(imageUrl)
                .defaultImage(defaultImage)
                .sortOrder(0)
                .build();

        int insertAt = resolveInsertPosition(position, product.getImages().size());
        product.getImages().add(insertAt, image);
        normalizeDefaultImage(product.getImages());
        reindexImages(product.getImages());

        return saveWithStagedDefaultImage(product);
    }

    @Transactional
    public Product setDefaultImage(Long productId, Long imageId, boolean defaultImage) {
        Product product = repo.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));

        TenantResolver.requireTenantId(product.getTenantId());

        ProductImage image = findImage(product, imageId);
        if (defaultImage) {
            clearDefaultImage(product.getImages());
        }
        image.setDefaultImage(defaultImage);
        normalizeDefaultImage(product.getImages());
        reindexImages(product.getImages());
        return saveWithStagedDefaultImage(product);
    }

    @Transactional
    public void deleteImage(Long productId, Long imageId) {
        Product product = repo.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));

        TenantResolver.requireTenantId(product.getTenantId());

        ProductImage image = findImage(product, imageId);
        product.getImages().remove(image);
        image.setProduct(null);
        normalizeDefaultImage(product.getImages());
        reindexImages(product.getImages());
        saveWithStagedDefaultImage(product);
    }

    @Transactional
    public void deleteById(Long id) { repo.deleteById(id); }

    @Transactional(readOnly = true)
    public List<Product> autocomplete(Long tenantId, String query, int limit) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        int normalizedLimit = Math.min(Math.max(limit, 1), 50);
        PageRequest page = PageRequest.of(0, normalizedLimit);
        if (!StringUtils.hasText(query)) {
            return repo.findByTenantIdOrderByDisplayOrderAscNameAscIdAsc(tenantId, page).getContent();
        }
        return repo.findByTenantIdAndNameContainingIgnoreCaseOrderByDisplayOrderAscNameAscIdAsc(
                tenantId,
                query.trim(),
                page
        ).getContent();
    }

    @Transactional(readOnly = true)
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
                .displayOrder(product.getDisplayOrder())
                .description(firstNonBlank(product.getDescription(), product.getName()))
                .defaultImageUrl(resolveDefaultImageUrl(product.getImages()))
                .galleryImages(product.getImages().stream()
                        .map(this::toGalleryImageDto)
                        .toList())
                .tax1Percent(product.getTax1Percent())
                .tax2Percent(product.getTax2Percent())
                .unitPriceGross(unitPriceGross)
                .currency(effectiveCurrency)
                .uom(effectiveUom)
                .priceDate(effectiveDate)
                .build();
    }

    private void copyEditableFields(Product source, Product target) {
        if (source.getTenantId() != null || target.getId() == null) {
            target.setTenantId(source.getTenantId());
        }
        target.setName(source.getName());
        target.setDisplayOrder(source.getDisplayOrder());
        target.setDescription(trimToNull(source.getDescription()));
        target.setResource(source.getResource());
        target.setDefaultUom(source.getDefaultUom());
        target.setProductType(source.getProductType());
        target.setTax1Percent(source.getTax1Percent());
        target.setTax2Percent(source.getTax2Percent());
        if (source.getExtraUoms() != null) {
            target.setExtraUoms(new HashSet<>(source.getExtraUoms()));
        } else if (target.getId() == null || target.getExtraUoms() == null) {
            target.setExtraUoms(new HashSet<>());
        }
        if (source.getImages() == null && target.getImages() == null) {
            target.setImages(new ArrayList<>());
        }
    }

    private void normalizeAndValidate(Product product) {
        product.setTenantId(TenantResolver.requireTenantId(product.getTenantId()));

        product.setName(trimToNull(product.getName()));
        if (!StringUtils.hasText(product.getName())) {
            throw new IllegalArgumentException("name is required");
        }

        if (product.getDisplayOrder() == null) {
            product.setDisplayOrder(0);
        }

        product.setDefaultUom(trimToNull(product.getDefaultUom()));
        if (!StringUtils.hasText(product.getDefaultUom())) {
            throw new IllegalArgumentException("defaultUom is required");
        }

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
        if (product.getExtraUoms() == null) {
            product.setExtraUoms(new HashSet<>());
        }
    }

    private void syncImages(Product product, List<ProductImage> requestedImages) {
        if (product.getImages() == null) {
            product.setImages(new ArrayList<>());
        }
        if (requestedImages == null) {
            normalizeDefaultImage(product.getImages());
            reindexImages(product.getImages());
            return;
        }

        Map<Long, ProductImage> existingById = new HashMap<>();
        for (ProductImage existing : product.getImages()) {
            if (existing.getId() != null) {
                existingById.put(existing.getId(), existing);
            }
        }

        List<ProductImage> normalized = new ArrayList<>();
        for (ProductImage requested : requestedImages) {
            ProductImage image;
            if (requested.getId() != null) {
                image = existingById.get(requested.getId());
                if (image == null) {
                    throw new IllegalArgumentException("Product image " + requested.getId() + " not found");
                }
            } else {
                image = new ProductImage();
            }

            image.setProduct(product);
            image.setImageUrl(trimToNull(requested.getImageUrl()));
            if (!StringUtils.hasText(image.getImageUrl())) {
                throw new IllegalArgumentException("product imageUrl is required");
            }
            image.setDefaultImage(Boolean.TRUE.equals(requested.getDefaultImage()));
            normalized.add(image);
        }

        normalizeDefaultImage(normalized);

        product.getImages().clear();
        product.getImages().addAll(normalized);
        reindexImages(product.getImages());
    }

    private void validateSingleDefaultImage(List<ProductImage> images) {
        long defaultCount = images.stream()
                .filter(image -> Boolean.TRUE.equals(image.getDefaultImage()))
                .count();
        if (defaultCount > 1) {
            throw new IllegalArgumentException("Only one product image can be marked as defaultImage");
        }
    }

    private void clearDefaultImage(List<ProductImage> images) {
        for (ProductImage image : images) {
            image.setDefaultImage(false);
        }
    }

    private void normalizeDefaultImage(List<ProductImage> images) {
        validateSingleDefaultImage(images);
        if (images.isEmpty()) {
            return;
        }
        boolean hasDefault = images.stream().anyMatch(image -> Boolean.TRUE.equals(image.getDefaultImage()));
        if (!hasDefault) {
            images.get(0).setDefaultImage(true);
        }
    }

    private void reindexImages(List<ProductImage> images) {
        for (int i = 0; i < images.size(); i++) {
            ProductImage image = images.get(i);
            image.setSortOrder(i);
            if (image.getDefaultImage() == null) {
                image.setDefaultImage(false);
            }
        }
    }

    private ProductImage findImage(Product product, Long imageId) {
        if (imageId == null) {
            throw new IllegalArgumentException("imageId is required");
        }
        return product.getImages().stream()
                .filter(image -> imageId.equals(image.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Product image not found"));
    }

    private int resolveInsertPosition(Integer requestedPosition, int size) {
        if (requestedPosition == null) {
            return size;
        }
        if (requestedPosition < 0) {
            return 0;
        }
        return Math.min(requestedPosition, size);
    }

    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        String contentType = file.getContentType();
        if (StringUtils.hasText(contentType)
                && !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new IllegalArgumentException("Only image files are supported");
        }
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String firstNonBlank(String first, String fallback) {
        if (StringUtils.hasText(first)) {
            return first.trim();
        }
        return fallback;
    }

    private String resolveDefaultImageUrl(List<ProductImage> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        return images.stream()
                .filter(image -> Boolean.TRUE.equals(image.getDefaultImage()))
                .findFirst()
                .or(() -> images.stream().findFirst())
                .map(ProductImage::getImageUrl)
                .orElse(null);
    }

    private ProductGalleryImageDto toGalleryImageDto(ProductImage image) {
        return ProductGalleryImageDto.builder()
                .id(image.getId())
                .imageUrl(image.getImageUrl())
                .defaultImage(Boolean.TRUE.equals(image.getDefaultImage()))
                .sortOrder(image.getSortOrder())
                .build();
    }

    private Product saveWithStagedDefaultImage(Product product) {
        if (product.getImages() == null || product.getImages().isEmpty()) {
            return repo.save(product);
        }

        normalizeDefaultImage(product.getImages());
        reindexImages(product.getImages());

        ProductImage desiredDefault = product.getImages().stream()
                .filter(image -> Boolean.TRUE.equals(image.getDefaultImage()))
                .findFirst()
                .orElse(null);

        clearDefaultImage(product.getImages());
        repo.saveAndFlush(product);

        if (desiredDefault == null) {
            return product;
        }

        desiredDefault.setDefaultImage(true);
        return repo.save(product);
    }
}
