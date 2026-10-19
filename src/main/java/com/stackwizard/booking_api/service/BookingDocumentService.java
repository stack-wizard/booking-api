package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.stackwizard.booking_api.dto.InvoiceCreateItemRequest;
import com.stackwizard.booking_api.dto.InvoiceCreateRequest;
import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.BookingFunctionItem;
import com.stackwizard.booking_api.model.BookingOrder;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceStatus;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.BookingFunctionItemRepository;
import com.stackwizard.booking_api.repository.BookingFunctionRepository;
import com.stackwizard.booking_api.repository.BookingOrderRepository;
import com.stackwizard.booking_api.repository.InvoiceRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BookingDocumentService {
    public static final String INVOICE_REFERENCE_TABLE = "booking";
    private static final Set<Booking.Status> BEO_STATUSES = EnumSet.of(Booking.Status.TENTATIVE, Booking.Status.DEFINITE, Booking.Status.ACTUAL);
    private static final Set<Booking.Status> INVOICE_STATUSES = EnumSet.of(Booking.Status.DEFINITE, Booking.Status.ACTUAL);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final BookingService bookingService;
    private final BookingFunctionRepository functionRepo;
    private final BookingFunctionItemRepository itemRepo;
    private final BookingOrderRepository orderRepo;
    private final BookingReservationSync reservationSync;
    private final ProductRepository productRepo;
    private final ResourceRepository resourceRepo;
    private final CrmAccountRepository accountRepo;
    private final CrmContactRepository contactRepo;
    private final InvoiceService invoiceService;
    private final InvoiceRepository invoiceRepo;
    private final CrmAccessContext accessContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BookingDocumentService(BookingService bookingService,
                                BookingFunctionRepository functionRepo,
                                BookingFunctionItemRepository itemRepo,
                                BookingOrderRepository orderRepo,
                                BookingReservationSync reservationSync,
                                ProductRepository productRepo,
                                ResourceRepository resourceRepo,
                                CrmAccountRepository accountRepo,
                                CrmContactRepository contactRepo,
                                InvoiceService invoiceService,
                                InvoiceRepository invoiceRepo,
                                CrmAccessContext accessContext) {
        this.bookingService = bookingService;
        this.functionRepo = functionRepo;
        this.itemRepo = itemRepo;
        this.orderRepo = orderRepo;
        this.reservationSync = reservationSync;
        this.productRepo = productRepo;
        this.resourceRepo = resourceRepo;
        this.accountRepo = accountRepo;
        this.contactRepo = contactRepo;
        this.invoiceService = invoiceService;
        this.invoiceRepo = invoiceRepo;
        this.accessContext = accessContext;
    }

    public List<BookingOrder> orders(Long bookingId) {
        Booking booking = bookingService.requireBooking(bookingId);
        return orderRepo.findByTenantIdAndBookingIdOrderByVersionDesc(booking.getTenantId(), booking.getId());
    }

    @Transactional
    public BookingOrder issueOrder(Long bookingId, String note) {
        accessContext.require(CrmPermission.EVENT_WRITE);
        Booking booking = bookingService.requireBooking(bookingId);
        if (!BEO_STATUSES.contains(booking.getStatus())) {
            throw new IllegalStateException("BEO can be issued for TENTATIVE, DEFINITE or ACTUAL bookings, not " + booking.getStatus());
        }
        List<BookingOrder> previous = orderRepo.findByTenantIdAndBookingIdOrderByVersionDesc(booking.getTenantId(), booking.getId());
        int version = previous.isEmpty() ? 1 : previous.getFirst().getVersion() + 1;
        for (BookingOrder order : previous) {
            if (order.getStatus() == BookingOrder.Status.ISSUED) {
                order.setStatus(BookingOrder.Status.SUPERSEDED);
            }
        }
        orderRepo.saveAll(previous);
        return orderRepo.save(BookingOrder.builder()
                .tenantId(booking.getTenantId())
                .bookingId(booking.getId())
                .version(version)
                .status(BookingOrder.Status.ISSUED)
                .snapshot(snapshot(booking, version))
                .note(note)
                .issuedBy(accessContext.currentUserId())
                .build());
    }

    @Transactional(readOnly = true)
    public PdfDocument orderPdf(Long orderId) {
        accessContext.require(CrmPermission.EVENT_READ);
        BookingOrder order = orderRepo.findByIdAndTenantId(orderId, TenantResolver.requireOrgTenantId())
                .orElseThrow(() -> new IllegalArgumentException("BEO not found: " + orderId));
        bookingService.requireBooking(order.getBookingId());
        String html = renderOrderHtml(order.getSnapshot(), order.getVersion(), order.getStatus());
        String name = "BEO-" + order.getBookingId() + "-v" + order.getVersion() + ".pdf";
        return new PdfDocument(name, renderPdf(html));
    }

    /** openhtmltopdf parses XHTML: named HTML entities such as {@code &middot;} are not allowed. */
    static byte[] renderPdf(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not render PDF: " + ex.getMessage(), ex);
        }
    }

    /**
     * Draft invoice for the booking: one line per rent line and per item. Reuses an existing draft.
     */
    @Transactional
    public Invoice createInvoice(Long bookingId) {
        accessContext.require(CrmPermission.EVENT_WRITE);
        Booking booking = bookingService.requireBooking(bookingId);
        if (!INVOICE_STATUSES.contains(booking.getStatus())) {
            throw new IllegalStateException("Invoice can be created for DEFINITE or ACTUAL bookings, not " + booking.getStatus());
        }
        Optional<Invoice> draft = invoiceService.findAllByReference(INVOICE_REFERENCE_TABLE, booking.getId()).stream()
                .filter(i -> i.getStatus() == InvoiceStatus.DRAFT)
                .findFirst();
        if (draft.isPresent()) {
            return draft.get();
        }
        List<BookingFunction> functions = functionRepo.findForBooking(
                booking.getTenantId(), booking.getId());
        List<Long> functionIds = functions.stream().map(BookingFunction::getId).toList();
        Map<Long, Reservation> rentByFunction = reservationSync.activeLines(functions).stream()
                .collect(Collectors.toMap(Reservation::getBookingFunctionId, Function.identity(), (a, b) -> a));
        Map<Long, List<BookingFunctionItem>> itemsByFunction = functionIds.isEmpty() ? Map.of()
                : itemRepo.findForFunctions(booking.getTenantId(), functionIds).stream()
                .collect(Collectors.groupingBy(BookingFunctionItem::getBookingFunctionId));

        List<InvoiceCreateItemRequest> lines = new ArrayList<>();
        for (BookingFunction function : functions) {
            Reservation rent = rentByFunction.get(function.getId());
            if (rent != null && rent.getQty() != null && rent.getQty() > 0
                    && rent.getUnitPrice() != null && rent.getUnitPrice().signum() > 0) {
                InvoiceCreateItemRequest line = new InvoiceCreateItemRequest();
                line.setProductId(rent.getProductId());
                line.setDescription(functionLabel(function) + " " + function.getStartsAt().toLocalDate());
                line.setQuantity(rent.getQty());
                line.setUom(rent.getUom());
                line.setUnitPriceGross(rent.getUnitPrice());
                lines.add(line);
            }
            for (BookingFunctionItem item : itemsByFunction.getOrDefault(function.getId(), List.of())) {
                InvoiceCreateItemRequest line = new InvoiceCreateItemRequest();
                line.setProductId(item.getProductId());
                line.setDescription(item.getDescription());
                line.setQuantity(item.getQty());
                line.setUom(item.getUom());
                line.setUnitPriceGross(item.getUnitPrice());
                line.setDiscountPercent(discountPercent(item));
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            throw new IllegalStateException("Booking has nothing to invoice");
        }
        CrmAccount account = accountRepo.findByIdAndTenantId(booking.getAccountId(), booking.getTenantId()).orElse(null);
        CrmContact contact = booking.getPrimaryContactId() == null ? null
                : contactRepo.findByIdAndTenantId(booking.getPrimaryContactId(), booking.getTenantId()).orElse(null);
        InvoiceCreateRequest request = new InvoiceCreateRequest();
        request.setTenantId(SalesContractService.invoicingHotel(booking));
        request.setCurrency(booking.getCurrency());
        request.setCustomerName(account != null ? firstNonBlank(account.getLegalName(), account.getName()) : booking.getName());
        request.setCustomerEmail(contact != null && contact.getEmail() != null ? contact.getEmail() : account != null ? account.getEmail() : null);
        request.setCustomerPhone(contact != null && contact.getPhone() != null ? contact.getPhone() : account != null ? account.getPhone() : null);
        request.setItems(lines);
        Invoice invoice = invoiceService.createManualDraft(request);
        invoice.setReferenceTable(INVOICE_REFERENCE_TABLE);
        invoice.setReferenceId(booking.getId());
        return invoiceRepo.save(invoice);
    }

    public List<Invoice> invoices(Long bookingId) {
        Booking booking = bookingService.requireBooking(bookingId);
        return invoiceService.findAllByReference(INVOICE_REFERENCE_TABLE, booking.getId());
    }

    JsonNode snapshot(Booking booking, int version) {
        List<BookingFunction> functions = functionRepo.findForBooking(
                booking.getTenantId(), booking.getId());
        List<Long> functionIds = functions.stream().map(BookingFunction::getId).toList();
        List<BookingFunctionItem> items = functionIds.isEmpty() ? List.of()
                : itemRepo.findForFunctions(booking.getTenantId(), functionIds);
        Map<Long, String> productNames = productRepo.findAllById(
                        items.stream().map(BookingFunctionItem::getProductId).distinct().toList()).stream()
                .collect(Collectors.toMap(Product::getId, Product::getName));
        Map<Long, String> spaceNames = resourceRepo.findAllById(
                        functions.stream().map(BookingFunction::getResourceId).filter(id -> id != null).distinct().toList()).stream()
                .collect(Collectors.toMap(Resource::getId, Resource::getName));
        CrmAccount account = accountRepo.findByIdAndTenantId(booking.getAccountId(), booking.getTenantId()).orElse(null);
        CrmContact contact = booking.getPrimaryContactId() == null ? null
                : contactRepo.findByIdAndTenantId(booking.getPrimaryContactId(), booking.getTenantId()).orElse(null);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", version);
        root.put("bookingId", booking.getId());
        root.put("name", booking.getName());
        root.put("status", booking.getStatus().name());
        root.put("eventType", booking.getEventType());
        root.put("dateFrom", booking.getDateFrom().toString());
        root.put("dateTo", booking.getDateTo().toString());
        root.put("account", account != null ? account.getName() : null);
        root.put("contact", contact != null ? (nullToEmpty(contact.getFirstName()) + " " + nullToEmpty(contact.getLastName())).trim() : null);
        root.put("contactPhone", contact != null ? contact.getPhone() : null);
        root.put("expectedPax", booking.getExpectedPax());
        root.put("guaranteedPax", booking.getGuaranteedPax());
        root.put("notes", booking.getNotes());
        ArrayNode functionNodes = root.putArray("functions");
        Map<Long, List<BookingFunctionItem>> itemsByFunction = items.stream()
                .collect(Collectors.groupingBy(BookingFunctionItem::getBookingFunctionId));
        for (BookingFunction function : functions) {
            ObjectNode node = functionNodes.addObject();
            node.put("id", function.getId());
            node.put("name", functionLabel(function));
            node.put("functionType", function.getFunctionType().name());
            node.put("space", spaceNames.get(function.getResourceId()));
            node.put("setupStyle", function.getSetupStyle());
            node.put("startsAt", function.getStartsAt().toString());
            node.put("endsAt", function.getEndsAt().toString());
            node.put("occupancyStartsAt", function.getOccupancyStartsAt().toString());
            node.put("occupancyEndsAt", function.getOccupancyEndsAt().toString());
            node.put("pax", function.getPax());
            node.put("notes", function.getNotes());
            ArrayNode itemNodes = node.putArray("items");
            for (BookingFunctionItem item : itemsByFunction.getOrDefault(function.getId(), List.of())) {
                ObjectNode itemNode = itemNodes.addObject();
                itemNode.put("product", productNames.get(item.getProductId()));
                itemNode.put("description", item.getDescription());
                itemNode.put("qty", item.getQty());
                itemNode.put("uom", item.getUom());
                itemNode.put("serveAt", item.getServeAt() != null ? item.getServeAt().toString() : null);
                itemNode.put("dietaryNotes", item.getDietaryNotes());
                ArrayNode allergens = itemNode.putArray("allergens");
                if (item.getAllergens() != null) {
                    item.getAllergens().forEach(allergens::add);
                }
            }
        }
        return root;
    }

    static String renderOrderHtml(JsonNode snapshot, int version, BookingOrder.Status status) {
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta charset=\"UTF-8\"/><style>")
                .append("body{font-family:sans-serif;font-size:10pt;color:#222}")
                .append("h1{font-size:16pt;margin:0}h2{font-size:12pt;margin:16px 0 4px;border-bottom:1px solid #999}")
                .append("table{width:100%;border-collapse:collapse}td,th{padding:3px 4px;text-align:left;vertical-align:top}")
                .append("th{background:#eee}.meta td{padding:1px 4px}.muted{color:#666}")
                .append("</style></head><body>");
        html.append("<h1>Banquet Booking Order — ").append(esc(text(snapshot, "name"))).append("</h1>");
        html.append("<p class=\"muted\">Version ").append(version).append(" · ").append(status.name())
                .append(" · ").append(esc(text(snapshot, "status"))).append("</p>");
        html.append("<table class=\"meta\">");
        row(html, "Account", text(snapshot, "account"));
        row(html, "Contact", join(text(snapshot, "contact"), text(snapshot, "contactPhone")));
        row(html, "Dates", text(snapshot, "dateFrom") + " – " + text(snapshot, "dateTo"));
        row(html, "Pax", "expected " + nullToDash(text(snapshot, "expectedPax")) + ", guaranteed " + nullToDash(text(snapshot, "guaranteedPax")));
        row(html, "Notes", text(snapshot, "notes"));
        html.append("</table>");
        for (JsonNode function : snapshot.path("functions")) {
            html.append("<h2>").append(esc(day(text(function, "startsAt")))).append(" ")
                    .append(esc(time(text(function, "startsAt")))).append("–").append(esc(time(text(function, "endsAt"))))
                    .append(" · ").append(esc(text(function, "name"))).append("</h2>");
            html.append("<table class=\"meta\">");
            row(html, "Space", join(text(function, "space"), text(function, "setupStyle")));
            row(html, "Access", time(text(function, "occupancyStartsAt")) + " – " + time(text(function, "occupancyEndsAt")));
            row(html, "Pax", text(function, "pax"));
            row(html, "Notes", text(function, "notes"));
            html.append("</table>");
            if (function.path("items").isEmpty()) {
                continue;
            }
            html.append("<table><tr><th>Time</th><th>Item</th><th>Qty</th><th>Dietary / allergens</th></tr>");
            for (JsonNode item : function.path("items")) {
                List<String> allergens = new ArrayList<>();
                item.path("allergens").forEach(a -> allergens.add(a.asText()));
                html.append("<tr><td>").append(esc(time(text(item, "serveAt")))).append("</td><td>")
                        .append(esc(join(text(item, "product"), text(item, "description")))).append("</td><td>")
                        .append(esc(text(item, "qty"))).append(" ").append(esc(text(item, "uom"))).append("</td><td>")
                        .append(esc(join(text(item, "dietaryNotes"), String.join(", ", allergens)))).append("</td></tr>");
            }
            html.append("</table>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    private static void row(StringBuilder html, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        html.append("<tr><td class=\"muted\" style=\"width:90px\">").append(esc(label)).append("</td><td>")
                .append(esc(value)).append("</td></tr>");
    }

    private static BigDecimal discountPercent(BookingFunctionItem item) {
        if (item.getDiscountAmount() == null || item.getDiscountAmount().signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal gross = item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQty()));
        if (gross.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return item.getDiscountAmount().multiply(BigDecimal.valueOf(100)).divide(gross, 4, RoundingMode.HALF_UP);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String day(String isoDateTime) {
        return isoDateTime == null ? "" : isoDateTime.substring(0, Math.min(10, isoDateTime.length()));
    }

    private static String time(String isoDateTime) {
        if (isoDateTime == null) {
            return "";
        }
        try {
            return java.time.LocalDateTime.parse(isoDateTime).format(TIME);
        } catch (RuntimeException ex) {
            return isoDateTime;
        }
    }

    private static String join(String a, String b) {
        boolean hasA = a != null && !a.isBlank();
        boolean hasB = b != null && !b.isBlank();
        if (hasA && hasB) {
            return a + " · " + b;
        }
        return hasA ? a : hasB ? b : null;
    }

    static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String nullToDash(String value) {
        return value == null ? "—" : value;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String functionLabel(BookingFunction function) {
        return function.getName() != null ? function.getName() : function.getFunctionType().name();
    }

    public record PdfDocument(String fileName, byte[] content) {
    }
}
