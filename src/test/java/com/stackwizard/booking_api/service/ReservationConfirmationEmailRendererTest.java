package com.stackwizard.booking_api.service;
import com.stackwizard.booking_api.dto.PublicCancellationPreviewDto;
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
                emailConfig,
                PublicCancellationPreviewDto.builder()
                        .canCancel(true)
                        .status("AVAILABLE")
                        .settlementMode("CASH_REFUND")
                        .currency("EUR")
                        .refundAmount(new BigDecimal("147.50"))
                        .message("If you cancel now, 147.50 EUR will be refunded.")
                        .policyText("Free cancellation until April 1, 2026.")
                        .build(),
                "https://cms.example.com/bookings/285?token=test-token"
        );

        assertThat(rendered.subject()).isEqualTo("Reservation confirmed - Beach Club Hvar #285");
        assertThat(rendered.html()).contains("Reservation Confirmed");
        assertThat(rendered.html()).contains("Luxury Sunbed 5");
        assertThat(rendered.html()).contains("147.50 EUR");
        assertThat(rendered.html()).contains("One bottle of Prosecco upon arrival");
        assertThat(rendered.html()).contains("info@beachhvar.com");
        assertThat(rendered.html()).contains("Review Or Cancel Booking");
        assertThat(rendered.html()).contains("https://cms.example.com/bookings/285?token=test-token");
        assertThat(rendered.plainText()).contains("Reservation: #285");
        assertThat(rendered.plainText()).contains("Remaining at venue: 147.50 EUR");
        assertThat(rendered.plainText()).contains("If you cancel now, 147.50 EUR will be refunded.");
    }
}
