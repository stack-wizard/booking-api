package com.stackwizard.booking_api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Inquiry for an INQUIRY catalog ({@code POST /api/catalogs/{code}/inquiries}): contact and event data like
 * {@link CatalogInquiryRequest}, but with catalog {@code items} instead of spaces / services and no client prices.
 * Unknown JSON fields are rejected.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogItemsInquiryRequest {
    @NotBlank
    @Size(max = 200)
    private String companyName;

    @Size(max = 50)
    private String vatId;

    @NotBlank
    @Size(max = 100)
    private String firstName;

    @NotBlank
    @Size(max = 100)
    private String lastName;

    @NotBlank
    @Email
    @Size(max = 254)
    private String email;

    @Size(max = 50)
    private String phone;

    @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be an ISO 3166-1 alpha-2 code")
    private String country;

    /** Code of one of the catalog's event types ({@code GET /api/catalogs/{code}}); required when it offers any. */
    @Size(max = 40)
    private String eventType;

    @NotNull
    private LocalDate dateFrom;

    @NotNull
    private LocalDate dateTo;

    @NotNull
    @Min(1)
    @Max(10000)
    private Integer attendees;

    private CatalogDtos.DayFormat dayFormat;

    @NotEmpty
    @Size(max = 100)
    @Builder.Default
    private List<@NotNull @Valid Item> items = new ArrayList<>();

    @Size(max = 4000)
    private String notes;

    @Size(max = 2000)
    private String sourceUrl;

    @Size(max = 100)
    private String cmsInquiryId;

    @JsonIgnore
    private Set<String> unknownFields;

    @JsonAnySetter
    void unknownField(String name, Object value) {
        if (unknownFields == null) {
            unknownFields = new LinkedHashSet<>();
        }
        unknownFields.add(name);
    }

    @JsonIgnore
    @AssertTrue(message = "unknown field(s) are not allowed")
    public boolean isKnownFieldsOnly() {
        return unknownFields == null || unknownFields.isEmpty();
    }

    @JsonIgnore
    @AssertTrue(message = "dateTo must not be before dateFrom")
    public boolean isDateRangeValid() {
        return dateFrom == null || dateTo == null || !dateTo.isBefore(dateFrom);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        @NotNull
        @Positive
        private Long itemId;

        @DecimalMin("0")
        @Digits(integer = 9, fraction = 3)
        private BigDecimal quantity;

        @Size(max = 50)
        private String setupStyle;

        @JsonIgnore
        private Set<String> unknownFields;

        @JsonAnySetter
        void unknownField(String name, Object value) {
            if (unknownFields == null) {
                unknownFields = new LinkedHashSet<>();
            }
            unknownFields.add(name);
        }

        @JsonIgnore
        @AssertTrue(message = "unknown field(s) are not allowed")
        public boolean isKnownFieldsOnly() {
            return unknownFields == null || unknownFields.isEmpty();
        }
    }
}
