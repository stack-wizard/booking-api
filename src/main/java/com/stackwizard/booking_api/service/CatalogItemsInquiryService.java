package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.dto.CatalogDtos;
import com.stackwizard.booking_api.dto.CatalogItemsInquiryRequest;
import com.stackwizard.booking_api.model.CatalogItem;
import com.stackwizard.booking_api.model.CrmLead;
import com.stackwizard.booking_api.model.SalesCatalog;
import com.stackwizard.booking_api.repository.CrmLeadRepository;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Inquiry for an INQUIRY catalog: booking-api prices the selected items itself ({@link CatalogQuoteService}) and stores
 * the quote in the WEBSITE lead ({@code description} and {@code attrs.inquiry}). A repeated {@code cmsInquiryId}
 * returns the lead created the first time (same rule as {@code /api/catalog/inquiries}).
 */
@Service
public class CatalogItemsInquiryService {
    public record Result(CrmLead lead, boolean created, CatalogDtos.QuoteResponse quote) {
    }

    private final CrmLeadService leadService;
    private final CrmLeadRepository leadRepo;
    private final CatalogQuoteService quoteService;
    private final CatalogViewService viewService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CatalogItemsInquiryService(CrmLeadService leadService, CrmLeadRepository leadRepo,
                                      CatalogQuoteService quoteService, CatalogViewService viewService) {
        this.leadService = leadService;
        this.leadRepo = leadRepo;
        this.quoteService = quoteService;
        this.viewService = viewService;
    }

    @Transactional
    public Result submit(SalesCatalog catalog, CatalogItemsInquiryRequest request) {
        if (catalog.getSaleMode() != SalesCatalog.SaleMode.INQUIRY) {
            throw new IllegalArgumentException("Catalog " + catalog.getCode() + " is sold instantly (INSTANT), not by inquiry");
        }
        if (request.getDateTo().isBefore(request.getDateFrom())) {
            throw new IllegalArgumentException("dateTo must not be before dateFrom");
        }
        Long tenantId = TenantResolver.requireOrgTenantId();
        String externalRef = trimToNull(request.getCmsInquiryId());
        if (externalRef != null) {
            Optional<CrmLead> existing = leadRepo.findByTenantIdAndSourceAndExternalRef(tenantId, CatalogInquiryService.SOURCE, externalRef);
            if (existing.isPresent()) {
                return new Result(existing.get(), false, null);
            }
        }
        List<CatalogDtos.QuoteItem> quoteItems = request.getItems().stream()
                .map(i -> new CatalogDtos.QuoteItem(i.getItemId(), i.getQuantity()))
                .toList();
        CatalogDtos.QuoteResponse quote = quoteService.quote(catalog, new CatalogDtos.QuoteRequest(
                request.getDateFrom(), request.getDateTo(), request.getAttendees(), request.getDayFormat(), quoteItems));

        Map<Long, CatalogItem> items = new HashMap<>();
        viewService.offer(catalog).items().forEach(i -> items.put(i.getId(), i));
        CrmLead lead = CrmLead.builder()
                .companyName(request.getCompanyName().trim())
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .email(request.getEmail().trim())
                .phone(trimToNull(request.getPhone()))
                .country(trimToNull(request.getCountry()))
                .source(CatalogInquiryService.SOURCE)
                .externalRef(externalRef)
                .status(CrmLead.Status.NEW)
                .description(describe(catalog, request, quote))
                .attrs(attrs(catalog, request, quote, items))
                .build();
        return new Result(leadService.createFromChannel(lead), true, quote);
    }

    ObjectNode attrs(SalesCatalog catalog, CatalogItemsInquiryRequest r, CatalogDtos.QuoteResponse quote, Map<Long, CatalogItem> items) {
        ObjectNode attrs = objectMapper.createObjectNode();
        attrs.put("channel", CatalogInquiryService.CHANNEL);
        attrs.put("inquiryType", catalog.getCode());
        attrs.put("catalog", catalog.getCode());
        attrs.put("eventType", r.getEventType().name());
        attrs.put("startDate", r.getDateFrom().toString());
        attrs.put("endDate", r.getDateTo().toString());
        attrs.put("pax", r.getAttendees());
        r.getItems().stream().map(CatalogItemsInquiryRequest.Item::getSetupStyle).filter(StringUtils::hasText).findFirst()
                .ifPresent(style -> attrs.put("setupStyle", style.trim()));
        attrs.put("estimatedValue", quote.totalGross());

        ObjectNode n = attrs.putObject("inquiry");
        n.put("catalog", catalog.getCode());
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
        ArrayNode itemNodes = n.putArray("items");
        for (CatalogItemsInquiryRequest.Item i : r.getItems()) {
            ObjectNode in = itemNodes.addObject();
            in.put("itemId", i.getItemId());
            CatalogItem item = items.get(i.getItemId());
            if (item != null) {
                in.put("kind", item.getItemKind().name());
                putIfText(in, "group", item.getItemGroup());
                if (item.getResourceId() != null) {
                    in.put("resourceId", item.getResourceId());
                }
                if (item.getProductId() != null) {
                    in.put("productId", item.getProductId());
                }
            }
            if (i.getQuantity() != null) {
                in.put("quantity", i.getQuantity());
            }
            putIfText(in, "setupStyle", i.getSetupStyle());
        }
        n.set("quote", quoteNode(quote));
        putIfText(n, "notes", r.getNotes());
        putIfText(n, "sourceUrl", r.getSourceUrl());
        putIfText(n, "cmsInquiryId", r.getCmsInquiryId());
        return attrs;
    }

    /** The quote as stored on the lead (built by hand: dates as ISO strings, no Jackson modules needed). */
    ObjectNode quoteNode(CatalogDtos.QuoteResponse quote) {
        ObjectNode q = objectMapper.createObjectNode();
        q.put("currency", quote.currency());
        ArrayNode lines = q.putArray("lines");
        for (CatalogDtos.QuoteLine l : quote.lines()) {
            ObjectNode ln = lines.addObject();
            ln.put("itemId", l.itemId());
            ln.put("name", l.name());
            ln.put("date", l.date().toString());
            ln.put("days", l.days());
            ln.put("quantity", l.quantity());
            putIfText(ln, "uom", l.uom());
            if (l.unitPriceGross() != null) {
                ln.put("unitPriceGross", l.unitPriceGross());
                ln.put("totalGross", l.totalGross());
            } else {
                ln.putNull("unitPriceGross");
                ln.putNull("totalGross");
            }
            if (l.taxPercent() != null) {
                ln.put("taxPercent", l.taxPercent());
            }
            if (l.rule() != null) {
                ObjectNode rule = ln.putObject("rule");
                putIfText(rule, "season", l.rule().season());
                if (l.rule().periodKind() != null) {
                    rule.put("periodKind", l.rule().periodKind().name());
                }
                putIfText(rule, "daysOfWeek", l.rule().daysOfWeek());
            }
        }
        q.put("totalGross", quote.totalGross());
        q.put("totalNet", quote.totalNet());
        q.put("hasItemsOnRequest", quote.hasItemsOnRequest());
        return q;
    }

    String describe(SalesCatalog catalog, CatalogItemsInquiryRequest r, CatalogDtos.QuoteResponse quote) {
        StringBuilder sb = new StringBuilder();
        sb.append("Website inquiry (").append(catalog.getName()).append(")\n");
        sb.append("Event type: ").append(r.getEventType().name()).append('\n');
        long days = ChronoUnit.DAYS.between(r.getDateFrom(), r.getDateTo()) + 1;
        sb.append("Dates: ").append(r.getDateFrom()).append(" - ").append(r.getDateTo())
                .append(" (").append(days).append(days == 1 ? " day" : " days");
        if (r.getDayFormat() != null) {
            sb.append(", ").append(r.getDayFormat() == CatalogDtos.DayFormat.HALF_DAY ? "half day" : "full day");
        }
        sb.append(")\n");
        sb.append("Attendees: ").append(r.getAttendees()).append('\n');
        if (StringUtils.hasText(r.getVatId())) {
            sb.append("VAT ID: ").append(r.getVatId().trim()).append('\n');
        }
        String currency = " " + quote.currency();
        sb.append("Selection (indicative prices):\n");
        for (CatalogDtos.QuoteLine line : quote.lines()) {
            sb.append("- ").append(line.name()).append(", ").append(line.date());
            if (line.days() > 1) {
                sb.append(" +").append(line.days() - 1).append(line.days() == 2 ? " day" : " days");
            }
            sb.append(": ").append(plain(line.quantity()));
            if (line.uom() != null) {
                sb.append(' ').append(line.uom());
            }
            if (line.unitPriceGross() == null) {
                sb.append(" - price on request");
            } else {
                sb.append(" @ ").append(plain(line.unitPriceGross())).append(currency)
                        .append(" = ").append(plain(line.totalGross())).append(currency);
            }
            sb.append('\n');
        }
        sb.append("Estimated total: ").append(plain(quote.totalGross())).append(currency);
        if (quote.hasItemsOnRequest()) {
            sb.append(" (some items on request)");
        }
        sb.append('\n');
        List<String> styles = new ArrayList<>();
        r.getItems().stream().map(CatalogItemsInquiryRequest.Item::getSetupStyle).filter(StringUtils::hasText)
                .map(String::trim).distinct().forEach(styles::add);
        if (!styles.isEmpty()) {
            sb.append("Setup: ").append(String.join(", ", styles)).append('\n');
        }
        if (StringUtils.hasText(r.getNotes())) {
            sb.append("Notes:\n").append(r.getNotes().trim()).append('\n');
        }
        if (StringUtils.hasText(r.getSourceUrl())) {
            sb.append("Source page: ").append(r.getSourceUrl().trim()).append('\n');
        }
        return sb.toString().trim();
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
