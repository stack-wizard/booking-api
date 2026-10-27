package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.dto.CatalogInquiryRequest;
import com.stackwizard.booking_api.model.CrmLead;
import com.stackwizard.booking_api.repository.CrmLeadRepository;
import com.stackwizard.booking_api.security.ApiClientAccess;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Website (booking-cms) MICE inquiries: each becomes a CRM lead with {@code source = WEBSITE}, assigned by the
 * lead assignment rules. A repeated {@code cmsInquiryId} returns the lead created the first time.
 */
@Service
public class CatalogInquiryService {
    public static final String SOURCE = "WEBSITE";
    public static final String CHANNEL = "booking-cms";
    /** {@code attrs.inquiryType} for assignment rules ({@code crm_assignment_rule.inquiry_type}). */
    public static final String INQUIRY_TYPE = "MICE";

    public record Result(CrmLead lead, boolean created) {
    }

    private final CrmLeadService leadService;
    private final CrmLeadRepository leadRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CatalogInquiryService(CrmLeadService leadService, CrmLeadRepository leadRepo) {
        this.leadService = leadService;
        this.leadRepo = leadRepo;
    }

    @Transactional
    public Result submit(CatalogInquiryRequest request) {
        ApiClientAccess.requireApiClient();
        if (request.getDateTo().isBefore(request.getDateFrom())) {
            throw new IllegalArgumentException("dateTo must not be before dateFrom");
        }
        Long tenantId = TenantResolver.requireOrgTenantId();
        String externalRef = trimToNull(request.getCmsInquiryId());
        if (externalRef != null) {
            Optional<CrmLead> existing = leadRepo.findByTenantIdAndSourceAndExternalRef(tenantId, SOURCE, externalRef);
            if (existing.isPresent()) {
                return new Result(existing.get(), false);
            }
        }

        CrmLead lead = CrmLead.builder()
                .companyName(request.getCompanyName().trim())
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .email(request.getEmail().trim())
                .phone(trimToNull(request.getPhone()))
                .country(trimToNull(request.getCountry()))
                .source(SOURCE)
                .externalRef(externalRef)
                .status(CrmLead.Status.NEW)
                .description(describe(request))
                .attrs(attrs(request))
                .build();
        return new Result(leadService.createFromChannel(lead), true);
    }

    /** CRM intake keys (see {@code CrmOpportunityService.INTAKE_KEYS}) plus the full submitted inquiry. */
    ObjectNode attrs(CatalogInquiryRequest r) {
        ObjectNode attrs = objectMapper.createObjectNode();
        attrs.put("channel", CHANNEL);
        attrs.put("inquiryType", INQUIRY_TYPE);
        attrs.put("eventType", r.getEventType().name());
        attrs.put("startDate", r.getDateFrom().toString());
        attrs.put("endDate", r.getDateTo().toString());
        attrs.put("pax", r.getAttendees());
        spaces(r).stream().map(CatalogInquiryRequest.Space::getSetupStyle).filter(StringUtils::hasText).findFirst()
                .ifPresent(style -> attrs.put("setupStyle", style.trim()));
        if (r.getEstimatedTotal() != null) {
            attrs.put("estimatedValue", r.getEstimatedTotal());
        }
        attrs.set("inquiry", inquiry(r));
        return attrs;
    }

    private ObjectNode inquiry(CatalogInquiryRequest r) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("companyName", r.getCompanyName());
        putIfText(n, "vatId", r.getVatId());
        n.put("firstName", r.getFirstName());
        n.put("lastName", r.getLastName());
        n.put("email", r.getEmail());
        putIfText(n, "phone", r.getPhone());
        putIfText(n, "country", r.getCountry() == null ? null : r.getCountry().toUpperCase(Locale.ROOT));
        n.put("eventType", r.getEventType().name());
        n.put("dateFrom", r.getDateFrom().toString());
        n.put("dateTo", r.getDateTo().toString());
        n.put("attendees", r.getAttendees());
        if (r.getDayFormat() != null) {
            n.put("dayFormat", r.getDayFormat().name());
        }
        ArrayNode spaces = n.putArray("spaces");
        for (CatalogInquiryRequest.Space s : spaces(r)) {
            ObjectNode sn = spaces.addObject();
            sn.put("resourceId", s.getResourceId());
            putIfText(sn, "name", s.getName());
            putIfText(sn, "setupStyle", s.getSetupStyle());
        }
        ArrayNode services = n.putArray("services");
        for (CatalogInquiryRequest.Service s : services(r)) {
            ObjectNode sn = services.addObject();
            if (s.getProductId() != null) {
                sn.put("productId", s.getProductId());
            }
            sn.put("name", s.getName());
            sn.put("category", s.getCategory().name());
            sn.put("quantity", s.getQuantity());
            putIfText(sn, "unit", s.getUnit());
            if (s.getUnitPrice() != null) {
                sn.put("unitPrice", s.getUnitPrice());
            }
            if (s.getTotal() != null) {
                sn.put("total", s.getTotal());
            }
        }
        if (r.getEstimatedTotal() != null) {
            n.put("estimatedTotal", r.getEstimatedTotal());
        }
        putIfText(n, "currency", r.getCurrency() == null ? null : r.getCurrency().toUpperCase(Locale.ROOT));
        putIfText(n, "notes", r.getNotes());
        putIfText(n, "sourceUrl", r.getSourceUrl());
        putIfText(n, "cmsInquiryId", r.getCmsInquiryId());
        return n;
    }

    /** Readable summary for sales in the lead's description. */
    String describe(CatalogInquiryRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Website MICE inquiry").append('\n');
        sb.append("Event type: ").append(r.getEventType().name()).append('\n');
        long days = ChronoUnit.DAYS.between(r.getDateFrom(), r.getDateTo()) + 1;
        sb.append("Dates: ").append(r.getDateFrom()).append(" - ").append(r.getDateTo())
                .append(" (").append(days).append(days == 1 ? " day" : " days");
        if (r.getDayFormat() != null) {
            sb.append(", ").append(r.getDayFormat() == CatalogInquiryRequest.DayFormat.HALF_DAY ? "half day" : "full day");
        }
        sb.append(")\n");
        sb.append("Attendees: ").append(r.getAttendees()).append('\n');
        if (StringUtils.hasText(r.getVatId())) {
            sb.append("VAT ID: ").append(r.getVatId().trim()).append('\n');
        }
        String currency = StringUtils.hasText(r.getCurrency()) ? " " + r.getCurrency().toUpperCase(Locale.ROOT) : "";
        List<CatalogInquiryRequest.Space> spaces = spaces(r);
        if (!spaces.isEmpty()) {
            sb.append("Halls:\n");
            for (CatalogInquiryRequest.Space s : spaces) {
                sb.append("- ").append(StringUtils.hasText(s.getName()) ? s.getName().trim() : "resource " + s.getResourceId());
                if (StringUtils.hasText(s.getSetupStyle())) {
                    sb.append(" (").append(s.getSetupStyle().trim()).append(')');
                }
                sb.append('\n');
            }
        }
        List<CatalogInquiryRequest.Service> services = services(r);
        if (!services.isEmpty()) {
            sb.append("Services:\n");
            for (CatalogInquiryRequest.Service s : services) {
                sb.append("- [").append(s.getCategory().name()).append("] ").append(s.getName().trim())
                        .append(" x ").append(plain(s.getQuantity()));
                if (StringUtils.hasText(s.getUnit())) {
                    sb.append(' ').append(s.getUnit().trim());
                }
                if (s.getUnitPrice() != null) {
                    sb.append(" @ ").append(plain(s.getUnitPrice())).append(currency);
                }
                if (s.getTotal() != null) {
                    sb.append(" = ").append(plain(s.getTotal())).append(currency);
                }
                sb.append('\n');
            }
        }
        if (r.getEstimatedTotal() != null) {
            sb.append("Estimated total: ").append(plain(r.getEstimatedTotal())).append(currency).append('\n');
        }
        if (StringUtils.hasText(r.getNotes())) {
            sb.append("Notes:\n").append(r.getNotes().trim()).append('\n');
        }
        if (StringUtils.hasText(r.getSourceUrl())) {
            sb.append("Source page: ").append(r.getSourceUrl().trim()).append('\n');
        }
        return sb.toString().trim();
    }

    private static List<CatalogInquiryRequest.Space> spaces(CatalogInquiryRequest r) {
        return r.getSpaces() == null ? List.of() : r.getSpaces();
    }

    private static List<CatalogInquiryRequest.Service> services(CatalogInquiryRequest r) {
        return r.getServices() == null ? List.of() : r.getServices();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static void putIfText(ObjectNode node, String field, String value) {
        if (StringUtils.hasText(value)) {
            node.put(field, value.trim());
        }
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
