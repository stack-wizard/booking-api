package com.stackwizard.booking_api.service;
import com.stackwizard.booking_api.model.LocationNode;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.Resource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationConfirmationEmailRendererTest {

    @Test
    void rendersConfirmationEmailFromReservationData() {
        ReservationConfirmationEmailRenderer renderer = new ReservationConfirmationEmailRenderer();
        TenantEmailConfigResolver.EmailResolvedConfig emailConfig = new TenantEmailConfigResolver.EmailResolvedConfig(
                "smtp.example.com",
                587,
                "info@beachhvar.com",
                "secret",
                true,
                true,
                false,
                "info@beachhvar.com",
                "info@beachhvar.com",
                "Beach Club Hvar",
                "info@beachhvar.com",
                "Beach Club Hvar | Bonj Les Bains | Hvar, Croatia",
                "Please arrive at the reception desk upon arrival and present this confirmation.",
                "en"
        );

        Product product = Product.builder()
                .id(10L)
                .name("Luxury Sunbed")
                .description("Apartment 5, Sunbed 47, Sunbed 48\nOne bottle of Prosecco upon arrival\nTwo bottles of still water")
                .build();
        LocationNode location = LocationNode.builder()
                .name("Bonj Les Bains")
                .build();
        Resource resource = Resource.builder()
                .name("Luxury Sunbed 5")
                .code("Spot L5")
                .location(location)
                .product(product)
                .build();
        Reservation reservation = Reservation.builder()
                .id(285L)
                .productId(10L)
                .requestedResource(resource)
                .startsAt(LocalDateTime.of(2026, 5, 15, 10, 0))
                .endsAt(LocalDateTime.of(2026, 5, 15, 19, 0))
                .adults(2)
                .children(0)
                .infants(0)
                .currency("EUR")
                .grossAmount(new BigDecimal("295.00"))
                .build();
        ReservationRequest request = ReservationRequest.builder()
                .id(285L)
                .build();

        ReservationConfirmationEmailRenderer.RenderedEmail rendered = renderer.render(
                request,
                List.of(reservation),
                Map.of(product.getId(), product),
                new PaymentService.RequestPaymentSummary(
                        new BigDecimal("295.00"),
                        new BigDecimal("147.50"),
                        new BigDecimal("147.50"),
                        BigDecimal.ZERO,
                        "PAID"
                ),
                emailConfig
        );

        assertThat(rendered.subject()).isEqualTo("Reservation confirmed - Beach Club Hvar #285");
        assertThat(rendered.html()).contains("Reservation Confirmed");
        assertThat(rendered.html()).contains("Luxury Sunbed 5");
        assertThat(rendered.html()).contains("147.50 EUR");
        assertThat(rendered.html()).contains("One bottle of Prosecco upon arrival");
        assertThat(rendered.html()).contains("info@beachhvar.com");
        assertThat(rendered.plainText()).contains("Reservation: #285");
        assertThat(rendered.plainText()).contains("Remaining at venue: 147.50 EUR");
    }
}
