package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository repo;
    @Mock
    private PriceListEntryRepository priceListRepo;
    @Mock
    private MediaStorageService mediaStorageService;

    private ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(repo, priceListRepo, mediaStorageService);
    }

    @Test
    void saveAllowsPenaltyProductType() {
        Product product = Product.builder()
                .tenantId(1L)
                .name("Cancellation penalty")
                .displayOrder(10)
                .defaultUom("ITEM")
                .productType("penalty")
                .tax1Percent(BigDecimal.ZERO)
                .tax2Percent(BigDecimal.ZERO)
                .extraUoms(new HashSet<>())
                .build();

        when(repo.save(any(Product.class))).thenAnswer(invocation -> {
            Product saved = invocation.getArgument(0);
            saved.setId(55L);
            return saved;
        });

        Product saved = service.save(product);

        assertThat(saved.getId()).isEqualTo(55L);
        assertThat(saved.getProductType()).isEqualTo("PENALTY");
    }
}
