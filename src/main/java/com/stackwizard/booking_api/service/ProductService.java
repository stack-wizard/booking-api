package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class ProductService {
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
        return repo.save(product);
    }
    public void deleteById(Long id) { repo.deleteById(id); }
}
