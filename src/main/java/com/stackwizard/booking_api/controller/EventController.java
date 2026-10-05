package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.EventDtos;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.EventFunctionItem;
import com.stackwizard.booking_api.model.EventOrder;
import com.stackwizard.booking_api.model.EventStatusHistory;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.service.EventDocumentService;
import com.stackwizard.booking_api.service.EventFunctionService;
import com.stackwizard.booking_api.service.EventPackageService;
import com.stackwizard.booking_api.service.EventReadModelService;
import com.stackwizard.booking_api.service.EventService;
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
public class EventController {
    private final EventService eventService;
    private final EventFunctionService functionService;
    private final EventReadModelService readModelService;
    private final EventDocumentService documentService;
    private final EventPackageService packageService;

    public EventController(EventService eventService,
                           EventFunctionService functionService,
                           EventReadModelService readModelService,
                           EventDocumentService documentService,
                           EventPackageService packageService) {
        this.eventService = eventService;
        this.functionService = functionService;
        this.readModelService = readModelService;
        this.documentService = documentService;
        this.packageService = packageService;
    }

    @GetMapping("/events")
    public List<Event> events(@RequestParam(required = false) Event.Status status,
                              @RequestParam(required = false) Long accountId,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return eventService.findAll(status, accountId, from, to);
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<Event> event(@PathVariable Long id) {
        return eventService.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/events")
    public ResponseEntity<Event> create(@RequestBody Event event) {
        Event saved = eventService.create(event);
        return ResponseEntity.created(URI.create("/api/events/" + saved.getId())).body(saved);
    }

    @PutMapping("/events/{id}")
    public Event update(@PathVariable Long id, @RequestBody Event event) {
        return eventService.update(id, event);
    }

    @PutMapping("/events/{id}/status")
    public Event changeStatus(@PathVariable Long id, @RequestBody EventDtos.StatusChangeRequest request) {
        return eventService.changeStatus(id, request);
    }

    @GetMapping("/events/{id}/history")
    public List<EventStatusHistory> history(@PathVariable Long id) {
        return eventService.history(id);
    }

    @GetMapping("/crm/opportunities/{id}/events")
    public List<Event> opportunityEvents(@PathVariable Long id) {
        return eventService.forOpportunity(id);
    }

    @PostMapping("/crm/opportunities/{id}/events")
    public ResponseEntity<Event> createFromOpportunity(@PathVariable Long id) {
        Event saved = eventService.createFromOpportunity(id);
        return ResponseEntity.created(URI.create("/api/events/" + saved.getId())).body(saved);
    }

    @GetMapping("/events/{id}/functions")
    public List<EventFunction> functions(@PathVariable Long id) {
        return functionService.functions(id);
    }

    @PostMapping("/events/{id}/functions")
    public EventFunction createFunction(@PathVariable Long id, @RequestBody EventFunction function) {
        return functionService.createFunction(id, function);
    }

    @PutMapping("/events/functions/{functionId}")
    public EventFunction updateFunction(@PathVariable Long functionId, @RequestBody EventFunction function) {
        return functionService.updateFunction(functionId, function);
    }

    @DeleteMapping("/events/functions/{functionId}")
    public ResponseEntity<Void> deleteFunction(@PathVariable Long functionId) {
        functionService.deleteFunction(functionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/events/{id}/items")
    public List<EventFunctionItem> eventItems(@PathVariable Long id) {
        return functionService.itemsForEvent(id);
    }

    @GetMapping("/events/functions/{functionId}/items")
    public List<EventFunctionItem> items(@PathVariable Long functionId) {
        return functionService.items(functionId);
    }

    @PostMapping("/events/functions/{functionId}/items")
    public EventFunctionItem createItem(@PathVariable Long functionId, @RequestBody EventFunctionItem item) {
        return functionService.createItem(functionId, item);
    }

    @PutMapping("/events/items/{itemId}")
    public EventFunctionItem updateItem(@PathVariable Long itemId, @RequestBody EventFunctionItem item) {
        return functionService.updateItem(itemId, item);
    }

    @DeleteMapping("/events/items/{itemId}")
    public ResponseEntity<Void> deleteItem(@PathVariable Long itemId) {
        functionService.deleteItem(itemId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/events/{id}/apply-package")
    public List<EventFunction> applyPackage(@PathVariable Long id, @RequestBody EventDtos.ApplyPackageRequest request) {
        return packageService.apply(id, request);
    }

    @GetMapping("/events/{id}/financials")
    public EventDtos.Financials financials(@PathVariable Long id) {
        return readModelService.financials(id);
    }

    @GetMapping("/events/space-grid")
    public List<EventDtos.SpaceGridRow> spaceGrid(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(required = false) Long locationId) {
        return readModelService.spaceGrid(from, to, locationId);
    }

    @GetMapping("/events/{id}/orders")
    public List<EventOrder> orders(@PathVariable Long id) {
        return documentService.orders(id);
    }

    @PostMapping("/events/{id}/orders")
    public EventOrder issueOrder(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return documentService.issueOrder(id, body != null ? body.get("note") : null);
    }

    @GetMapping("/events/orders/{orderId}/pdf")
    public ResponseEntity<byte[]> orderPdf(@PathVariable Long orderId) {
        EventDocumentService.PdfDocument pdf = documentService.orderPdf(orderId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pdf.fileName() + "\"")
                .body(pdf.content());
    }

    @GetMapping("/events/{id}/invoices")
    public List<Invoice> invoices(@PathVariable Long id) {
        return documentService.invoices(id);
    }

    @PostMapping("/events/{id}/invoice")
    public Invoice createInvoice(@PathVariable Long id) {
        return documentService.createInvoice(id);
    }
}
