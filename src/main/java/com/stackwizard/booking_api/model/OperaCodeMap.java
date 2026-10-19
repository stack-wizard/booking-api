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
@Table(name = "opera_code_map")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperaCodeMap {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "opera_hotel_id", nullable = false)
    private Long operaHotelId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    /** Resource id (FUNCTION_SPACE), chain code list code (EVENT_TYPE, SETUP_STYLE) or price profile id (RATE_CODE). */
    @Column(name = "local_ref", nullable = false)
    private String localRef;

    @Column(name = "opera_code", nullable = false)
    private String operaCode;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum Kind {
        EVENT_TYPE, SETUP_STYLE, FUNCTION_SPACE, RATE_CODE, ROOM_TYPE;

        public OperaCode.Kind codeKind() {
            return OperaCode.Kind.valueOf(name());
        }
    }
}
