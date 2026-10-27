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
 * B2B (MICE) event inquiry submitted from the public website through booking-cms. Unknown JSON fields are rejected.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogInquiryRequest {
    public enum EventType { CONFERENCE, SEMINAR, TEAM_BUILDING, GALA, OTHER }

    public enum DayFormat { FULL_DAY, HALF_DAY }

    public enum ServiceCategory { CATERING, AV, OTHER }

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

    @NotNull
    private EventType eventType;

    @NotNull
    private LocalDate dateFrom;

    @NotNull
    private LocalDate dateTo;

    @NotNull
    @Min(1)
    @Max(10000)
    private Integer attendees;

    private DayFormat dayFormat;

    @Valid
    @Size(max = 20)
    @Builder.Default
    private List<@NotNull @Valid Space> spaces = new ArrayList<>();

    @Valid
    @Size(max = 100)
    @Builder.Default
    private List<@NotNull @Valid Service> services = new ArrayList<>();

    @DecimalMin("0")
    @Digits(integer = 12, fraction = 2)
    private BigDecimal estimatedTotal;

    @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be an ISO 4217 code")
    private String currency;

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

    @JsonIgnore
    @AssertTrue(message = "currency is required when prices are sent")
    public boolean isCurrencyPresentForPrices() {
        boolean priced = estimatedTotal != null
                || (services != null && services.stream()
                .anyMatch(s -> s != null && (s.getUnitPrice() != null || s.getTotal() != null)));
        return !priced || (currency != null && !currency.isBlank());
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Space {
        @NotNull
        @Positive
        private Long resourceId;

        @Size(max = 200)
        private String name;

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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Service {
        @Positive
        private Long productId;

        @NotBlank
        @Size(max = 200)
        private String name;

        @NotNull
        private ServiceCategory category;

        @NotNull
        @DecimalMin("0")
        @Digits(integer = 9, fraction = 3)
        private BigDecimal quantity;

        @Size(max = 30)
        private String unit;

        @DecimalMin("0")
        @Digits(integer = 12, fraction = 2)
        private BigDecimal unitPrice;

        @DecimalMin("0")
        @Digits(integer = 12, fraction = 2)
        private BigDecimal total;

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
