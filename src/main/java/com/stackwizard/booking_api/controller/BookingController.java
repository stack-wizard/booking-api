package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.BookingDtos;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.BookingFunctionItem;
import com.stackwizard.booking_api.model.BookingOrder;
import com.stackwizard.booking_api.model.BookingStatus;
import com.stackwizard.booking_api.model.BookingStatusHistory;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.service.BookingDocumentService;
import com.stackwizard.booking_api.service.BookingFunctionService;
import com.stackwizard.booking_api.service.BookingPackageService;
import com.stackwizard.booking_api.service.BookingReadModelService;
import com.stackwizard.booking_api.service.BookingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BookingController {
    private final BookingService bookingService;
    private final BookingFunctionService functionService;
    private final BookingReadModelService readModelService;
    private final BookingDocumentService documentService;
    private final BookingPackageService packageService;

    public BookingController(BookingService bookingService,
                           BookingFunctionService functionService,
                           BookingReadModelService readModelService,
                           BookingDocumentService documentService,
                           BookingPackageService packageService) {
        this.bookingService = bookingService;
        this.functionService = functionService;
        this.readModelService = readModelService;
        this.documentService = documentService;
        this.packageService = packageService;
    }

    @GetMapping("/bookings")
    public List<Booking> bookings(@RequestParam(required = false) Booking.Status status,
                              @RequestParam(required = false) Long accountId,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return bookingService.findAll(status, accountId, from, to);
    }

    @GetMapping("/bookings/{id}")
    public ResponseEntity<Booking> booking(@PathVariable Long id) {
        return bookingService.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/bookings")
    public ResponseEntity<Booking> create(@RequestBody Booking booking) {
        Booking saved = bookingService.create(booking);
        return ResponseEntity.created(URI.create("/api/bookings/" + saved.getId())).body(saved);
    }

    @PutMapping("/bookings/{id}")
    public Booking update(@PathVariable Long id, @RequestBody Booking booking) {
        return bookingService.update(id, booking);
    }

    @PutMapping("/bookings/{id}/status")
    public Booking changeStatus(@PathVariable Long id, @RequestBody BookingDtos.StatusChangeRequest request) {
        return bookingService.changeStatus(id, request);
    }

    @GetMapping("/bookings/{id}/next-statuses")
    public List<BookingStatus> nextStatuses(@PathVariable Long id) {
        return bookingService.nextStatuses(id);
    }

    @GetMapping("/bookings/{id}/history")
    public List<BookingStatusHistory> history(@PathVariable Long id) {
        return bookingService.history(id);
    }

    @GetMapping("/crm/opportunities/{id}/booking-summaries")
    public List<BookingDtos.BookingSummary> opportunityBookingSummaries(@PathVariable Long id) {
        return readModelService.opportunitySummaries(id);
    }

    @GetMapping("/crm/opportunities/{id}/bookings")
    public List<Booking> opportunityBookings(@PathVariable Long id) {
        return bookingService.forOpportunity(id);
    }

    @PostMapping("/crm/opportunities/{id}/bookings")
    public ResponseEntity<Booking> createFromOpportunity(@PathVariable Long id) {
        Booking saved = bookingService.createFromOpportunity(id);
        return ResponseEntity.created(URI.create("/api/bookings/" + saved.getId())).body(saved);
    }

    @GetMapping("/bookings/{id}/functions")
    public List<BookingFunction> functions(@PathVariable Long id) {
        return functionService.functions(id);
    }

    @PostMapping("/bookings/{id}/functions")
    public BookingFunction createFunction(@PathVariable Long id, @RequestBody BookingFunction function) {
        return functionService.createFunction(id, function);
    }

    @PutMapping("/bookings/functions/{functionId}")
    public BookingFunction updateFunction(@PathVariable Long functionId, @RequestBody BookingFunction function) {
        return functionService.updateFunction(functionId, function);
    }

    @DeleteMapping("/bookings/functions/{functionId}")
    public ResponseEntity<Void> deleteFunction(@PathVariable Long functionId) {
        functionService.deleteFunction(functionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/bookings/{id}/items")
    public List<BookingFunctionItem> bookingItems(@PathVariable Long id) {
        return functionService.itemsForBooking(id);
    }

    @GetMapping("/bookings/functions/{functionId}/items")
    public List<BookingFunctionItem> items(@PathVariable Long functionId) {
        return functionService.items(functionId);
    }

    @PostMapping("/bookings/functions/{functionId}/items")
    public BookingFunctionItem createItem(@PathVariable Long functionId, @RequestBody BookingFunctionItem item) {
        return functionService.createItem(functionId, item);
    }

    @PutMapping("/bookings/items/{itemId}")
    public BookingFunctionItem updateItem(@PathVariable Long itemId, @RequestBody BookingFunctionItem item) {
        return functionService.updateItem(itemId, item);
    }

    @DeleteMapping("/bookings/items/{itemId}")
    public ResponseEntity<Void> deleteItem(@PathVariable Long itemId) {
        functionService.deleteItem(itemId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bookings/{id}/apply-package")
    public List<BookingFunction> applyPackage(@PathVariable Long id, @RequestBody BookingDtos.ApplyPackageRequest request) {
        return packageService.apply(id, request);
    }

    @GetMapping("/bookings/{id}/financials")
    public BookingDtos.Financials financials(@PathVariable Long id) {
        return readModelService.financials(id);
    }

    @GetMapping("/bookings/space-grid")
    public List<BookingDtos.SpaceGridRow> spaceGrid(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(required = false) Long locationId,
                                                  @RequestParam(required = false) String scope) {
        return readModelService.spaceGrid(from, to, locationId, scope);
    }

    @GetMapping("/bookings/room-grid")
    public List<BookingDtos.RoomGridRow> roomGrid(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(required = false) String scope,
                                                  @RequestParam(required = false, defaultValue = "true") boolean includeTentative) {
        return readModelService.roomGrid(from, to, scope, includeTentative);
    }

    @GetMapping("/bookings/{id}/orders")
    public List<BookingOrder> orders(@PathVariable Long id) {
        return documentService.orders(id);
    }

    @PostMapping("/bookings/{id}/orders")
    public BookingOrder issueOrder(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return documentService.issueOrder(id, body != null ? body.get("note") : null);
    }

    @GetMapping("/bookings/orders/{orderId}/pdf")
    public ResponseEntity<byte[]> orderPdf(@PathVariable Long orderId) {
        BookingDocumentService.PdfDocument pdf = documentService.orderPdf(orderId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pdf.fileName() + "\"")
                .body(pdf.content());
    }

    @GetMapping("/bookings/{id}/invoices")
    public List<Invoice> invoices(@PathVariable Long id) {
        return documentService.invoices(id);
    }

    @PostMapping("/bookings/{id}/invoice")
    public Invoice createInvoice(@PathVariable Long id) {
        return documentService.createInvoice(id);
    }
}
