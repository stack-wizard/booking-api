package com.stackwizard.booking_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "opera_hotel")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperaHotel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "hotel_code", nullable = false)
    private String hotelCode;

    @Column(name = "chain_code")
    private String chainCode;

    @Column
    private String name;

    @Column(name = "default_cashier_id")
    private Long defaultCashierId;

    @Column(name = "default_folio_window_no")
    private Integer defaultFolioWindowNo;

    @Column(name = "checkin_deposit_payment_trx_code")
    private String checkinDepositPaymentTrxCode;

    @Column(name = "checkin_deposit_payment_method_code")
    private String checkinDepositPaymentMethodCode;

    @Column(nullable = false)
    private Boolean active;

    /** Where function spaces are booked: our allocation (LOCAL) or OPERA blocks and events. */
    @Enumerated(EnumType.STRING)
    @Column(name = "space_inventory", nullable = false)
    private Mode spaceInventory;

    /** Where room blocks are held: our allocation (LOCAL) or the OPERA block room grid. */
    @Enumerated(EnumType.STRING)
    @Column(name = "room_inventory", nullable = false)
    @Builder.Default
    private Mode roomInventory = Mode.LOCAL;

    /** Who issues the event bill: our invoices (LOCAL) or OPERA folios (OPERA). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Mode billing;

    @Column(name = "default_market_code")
    private String defaultMarketCode;

    @Column(name = "default_source_code")
    private String defaultSourceCode;

    @Column(name = "default_non_deduct_reservation_type")
    private String defaultNonDeductReservationType;

    @Column(name = "default_deduct_reservation_type")
    private String defaultDeductReservationType;

    /**
     * Function-space rental code for OPERA events (Daily/Hourly/Per Person/CUSTOM), not a room rate plan.
     * Null means {@code CUSTOM} with an explicit rental amount.
     */
    @Column(name = "default_rental_code")
    private String defaultRentalCode;

    /** OPERA block origin code (e.g. EMAIL). */
    @Column(name = "default_origin_code")
    private String defaultOriginCode;

    /** OPERA block / rooms / catering owner code (typically ALL). */
    @Column(name = "default_block_owner_code")
    private String defaultBlockOwnerCode;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum Mode {
        LOCAL, OPERA
    }
}
