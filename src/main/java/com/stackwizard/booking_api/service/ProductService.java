package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
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

    public ProductService(ProductRepository repo) { this.repo = repo; }

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
}
