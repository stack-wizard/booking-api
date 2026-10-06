package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.SalesDtos;
import com.stackwizard.booking_api.security.PortalAccessContext;
import com.stackwizard.booking_api.service.EventDocumentService;
import com.stackwizard.booking_api.service.InvoicePdfService;
import com.stackwizard.booking_api.service.PortalService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Client portal. {@code /api/public/portal/{token}} is the anonymous link (one event);
 * {@code /api/portal} is the same view for a signed-in Platform user linked to a CRM contact.
 */
@RestController
public class PortalController {
    private final PortalService service;

    public PortalController(PortalService service) {
        this.service = service;
    }

    @GetMapping({"/api/public/portal/{token}", "/booking-api/api/public/portal/{token}"})
    public PortalService.PortalEventView byToken(@PathVariable String token) {
        PortalAccessContext ctx = service.fromToken(token);
        return service.event(ctx, ctx.eventIds().iterator().next());
    }

    @PostMapping({"/api/public/portal/{token}/quotes/{quoteId}/accept", "/booking-api/api/public/portal/{token}/quotes/{quoteId}/accept"})
    public PortalService.PortalEventView acceptByToken(@PathVariable String token, @PathVariable Long quoteId,
                                                       @RequestBody(required = false) SalesDtos.DecisionRequest request) {
        return service.decide(service.fromToken(token), quoteId, true, request);
    }

    @PostMapping({"/api/public/portal/{token}/quotes/{quoteId}/reject", "/booking-api/api/public/portal/{token}/quotes/{quoteId}/reject"})
    public PortalService.PortalEventView rejectByToken(@PathVariable String token, @PathVariable Long quoteId,
                                                       @RequestBody(required = false) SalesDtos.DecisionRequest request) {
        return service.decide(service.fromToken(token), quoteId, false, request);
    }

    @GetMapping({"/api/public/portal/{token}/quotes/{quoteId}/pdf", "/booking-api/api/public/portal/{token}/quotes/{quoteId}/pdf"})
    public ResponseEntity<byte[]> quotePdfByToken(@PathVariable String token, @PathVariable Long quoteId) {
        return SalesController.pdf(service.quotePdf(service.fromToken(token), quoteId));
    }

    @GetMapping({"/api/public/portal/{token}/contracts/{contractId}/pdf", "/booking-api/api/public/portal/{token}/contracts/{contractId}/pdf"})
    public ResponseEntity<byte[]> contractPdfByToken(@PathVariable String token, @PathVariable Long contractId) {
        return SalesController.pdf(service.contractPdf(service.fromToken(token), contractId));
    }

    @GetMapping({"/api/public/portal/{token}/documents/{documentId}", "/booking-api/api/public/portal/{token}/documents/{documentId}"})
    public ResponseEntity<byte[]> documentByToken(@PathVariable String token, @PathVariable Long documentId) {
        return SalesController.file(service.document(service.fromToken(token), documentId));
    }

    @GetMapping({"/api/public/portal/{token}/invoices/{invoiceId}/pdf", "/booking-api/api/public/portal/{token}/invoices/{invoiceId}/pdf"})
    public ResponseEntity<byte[]> invoicePdfByToken(@PathVariable String token, @PathVariable Long invoiceId) {
        PortalAccessContext ctx = service.fromToken(token);
        return invoicePdf(service.invoicePdf(ctx, ctx.eventIds().iterator().next(), invoiceId));
    }

    @GetMapping("/api/portal/events")
    public List<PortalService.PortalEventSummary> myEvents() {
        return service.events(service.fromPlatformUser());
    }

    @GetMapping("/api/portal/events/{eventId}")
    public PortalService.PortalEventView myEvent(@PathVariable Long eventId) {
        return service.event(service.fromPlatformUser(), eventId);
    }

    @PostMapping("/api/portal/quotes/{quoteId}/accept")
    public PortalService.PortalEventView accept(@PathVariable Long quoteId,
                                                @RequestBody(required = false) SalesDtos.DecisionRequest request) {
        return service.decide(service.fromPlatformUser(), quoteId, true, request);
    }

    @PostMapping("/api/portal/quotes/{quoteId}/reject")
    public PortalService.PortalEventView reject(@PathVariable Long quoteId,
                                                @RequestBody(required = false) SalesDtos.DecisionRequest request) {
        return service.decide(service.fromPlatformUser(), quoteId, false, request);
    }

    @GetMapping("/api/portal/quotes/{quoteId}/pdf")
    public ResponseEntity<byte[]> quotePdf(@PathVariable Long quoteId) {
        return SalesController.pdf(service.quotePdf(service.fromPlatformUser(), quoteId));
    }

    @GetMapping("/api/portal/contracts/{contractId}/pdf")
    public ResponseEntity<byte[]> contractPdf(@PathVariable Long contractId) {
        return SalesController.pdf(service.contractPdf(service.fromPlatformUser(), contractId));
    }

    @GetMapping("/api/portal/documents/{documentId}")
    public ResponseEntity<byte[]> document(@PathVariable Long documentId) {
        return SalesController.file(service.document(service.fromPlatformUser(), documentId));
    }

    @GetMapping("/api/portal/events/{eventId}/invoices/{invoiceId}/pdf")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable Long eventId, @PathVariable Long invoiceId) {
        return invoicePdf(service.invoicePdf(service.fromPlatformUser(), eventId, invoiceId));
    }

    private static ResponseEntity<byte[]> invoicePdf(InvoicePdfService.InvoicePdfDocument pdf) {
        return SalesController.pdf(new EventDocumentService.PdfDocument(pdf.fileName(), pdf.content()));
    }
}
