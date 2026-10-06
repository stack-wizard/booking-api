package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.SalesDtos;
import com.stackwizard.booking_api.model.CrmCostItem;
import com.stackwizard.booking_api.model.SalesContractDocument;
import com.stackwizard.booking_api.model.SalesQuote;
import com.stackwizard.booking_api.model.SalesQuoteLine;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.service.CrmCostService;
import com.stackwizard.booking_api.service.EventService;
import com.stackwizard.booking_api.service.PortalTokenService;
import com.stackwizard.booking_api.service.EventDocumentService;
import com.stackwizard.booking_api.service.SalesContractService;
import com.stackwizard.booking_api.service.SalesDocumentService;
import com.stackwizard.booking_api.service.SalesQuoteService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SalesController {
    private final SalesQuoteService quoteService;
    private final SalesContractService contractService;
    private final SalesDocumentService documentService;
    private final CrmCostService costService;
    private final PortalTokenService portalTokenService;
    private final EventService eventService;
    private final CrmAccessContext accessContext;

    public SalesController(SalesQuoteService quoteService,
                           SalesContractService contractService,
                           SalesDocumentService documentService,
                           CrmCostService costService,
                           PortalTokenService portalTokenService,
                           EventService eventService,
                           CrmAccessContext accessContext) {
        this.quoteService = quoteService;
        this.contractService = contractService;
        this.documentService = documentService;
        this.costService = costService;
        this.portalTokenService = portalTokenService;
        this.eventService = eventService;
        this.accessContext = accessContext;
    }

    @GetMapping("/events/{eventId}/portal-link")
    public Map<String, String> portalLink(@PathVariable Long eventId) {
        Event event = eventService.requireEvent(eventId);
        return Collections.singletonMap("url",
                portalTokenService.activeToken(event).map(portalTokenService::portalUrl).orElse(null));
    }

    @PostMapping("/events/{eventId}/portal-link")
    public Map<String, String> issuePortalLink(@PathVariable Long eventId) {
        Event event = eventService.requireEditable(eventId);
        return Map.of("url", portalTokenService.portalUrl(portalTokenService.ensureToken(event, accessContext.currentUserId())));
    }

    @DeleteMapping("/events/{eventId}/portal-link")
    public ResponseEntity<Void> revokePortalLink(@PathVariable Long eventId) {
        portalTokenService.revokeAll(eventService.requireEditable(eventId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/events/{eventId}/quotes")
    public List<SalesQuote> eventQuotes(@PathVariable Long eventId) {
        return quoteService.forEvent(eventId);
    }

    @PostMapping("/events/{eventId}/quotes")
    public SalesDtos.QuoteView createQuote(@PathVariable Long eventId) {
        return quoteService.view(quoteService.create(eventId).getId());
    }

    @GetMapping("/quotes/pending-approval")
    public List<SalesQuote> pendingApprovals() {
        return quoteService.pendingApprovals();
    }

    @GetMapping("/quotes/{id}")
    public SalesDtos.QuoteView quote(@PathVariable Long id) {
        return quoteService.view(id);
    }

    @PutMapping("/quotes/{id}")
    public SalesDtos.QuoteView updateQuote(@PathVariable Long id, @RequestBody SalesDtos.QuoteHeaderRequest request) {
        quoteService.updateHeader(id, request);
        return quoteService.view(id);
    }

    @PostMapping("/quotes/{id}/lines")
    public SalesQuoteLine addLine(@PathVariable Long id, @RequestBody SalesDtos.QuoteLineRequest request) {
        return quoteService.addLine(id, request);
    }

    @PutMapping("/quotes/{id}/lines/{lineId}")
    public SalesQuoteLine updateLine(@PathVariable Long id, @PathVariable Long lineId,
                                     @RequestBody SalesDtos.QuoteLineRequest request) {
        return quoteService.updateLine(id, lineId, request);
    }

    @DeleteMapping("/quotes/{id}/lines/{lineId}")
    public ResponseEntity<Void> deleteLine(@PathVariable Long id, @PathVariable Long lineId) {
        quoteService.deleteLine(id, lineId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/quotes/{id}/request-approval")
    public SalesDtos.QuoteView requestApproval(@PathVariable Long id, @RequestBody(required = false) SalesDtos.NoteRequest request) {
        quoteService.requestApproval(id, request != null ? request.note() : null);
        return quoteService.view(id);
    }

    @PostMapping("/quotes/{id}/approve")
    public SalesDtos.QuoteView approve(@PathVariable Long id, @RequestBody(required = false) SalesDtos.NoteRequest request) {
        quoteService.decideApproval(id, true, request != null ? request.note() : null);
        return quoteService.view(id);
    }

    @PostMapping("/quotes/{id}/decline-approval")
    public SalesDtos.QuoteView declineApproval(@PathVariable Long id, @RequestBody(required = false) SalesDtos.NoteRequest request) {
        quoteService.decideApproval(id, false, request != null ? request.note() : null);
        return quoteService.view(id);
    }

    @PostMapping("/quotes/{id}/send")
    public SalesDtos.QuoteView send(@PathVariable Long id) {
        return quoteService.send(id);
    }

    @PostMapping("/quotes/{id}/revise")
    public SalesDtos.QuoteView revise(@PathVariable Long id) {
        quoteService.revise(id);
        return quoteService.view(id);
    }

    @PostMapping("/quotes/{id}/accept")
    public SalesDtos.QuoteView accept(@PathVariable Long id, @RequestBody(required = false) SalesDtos.DecisionRequest request) {
        quoteService.decide(id, true, request);
        return quoteService.view(id);
    }

    @PostMapping("/quotes/{id}/reject")
    public SalesDtos.QuoteView reject(@PathVariable Long id, @RequestBody(required = false) SalesDtos.DecisionRequest request) {
        quoteService.decide(id, false, request);
        return quoteService.view(id);
    }

    @GetMapping("/quotes/{id}/pdf")
    public ResponseEntity<byte[]> quotePdf(@PathVariable Long id) {
        return pdf(documentService.quotePdf(id));
    }

    @GetMapping("/events/{eventId}/contracts")
    public List<SalesDtos.ContractView> eventContracts(@PathVariable Long eventId) {
        return contractService.forEvent(eventId);
    }

    @PostMapping("/events/{eventId}/contracts")
    public SalesDtos.ContractView createContract(@PathVariable Long eventId,
                                                 @RequestBody(required = false) SalesDtos.ContractRequest request) {
        return contractService.create(eventId, request);
    }

    @GetMapping("/contracts/{id}")
    public SalesDtos.ContractView contract(@PathVariable Long id) {
        return contractService.get(id);
    }

    @PutMapping("/contracts/{id}")
    public SalesDtos.ContractView updateContract(@PathVariable Long id, @RequestBody SalesDtos.ContractRequest request) {
        return contractService.update(id, request);
    }

    @PutMapping("/contracts/{id}/milestones")
    public SalesDtos.ContractView milestones(@PathVariable Long id, @RequestBody List<SalesDtos.MilestoneRequest> request) {
        return contractService.replaceMilestones(id, request);
    }

    @PostMapping("/contracts/{id}/send")
    public SalesDtos.ContractView sendContract(@PathVariable Long id) {
        return contractService.send(id);
    }

    @PostMapping("/contracts/{id}/sign")
    public SalesDtos.ContractView sign(@PathVariable Long id, @RequestBody SalesDtos.SignRequest request) {
        return contractService.sign(id, request);
    }

    @PostMapping("/contracts/{id}/cancel")
    public SalesDtos.ContractView cancelContract(@PathVariable Long id) {
        return contractService.cancel(id);
    }

    @GetMapping("/contracts/{id}/pdf")
    public ResponseEntity<byte[]> contractPdf(@PathVariable Long id) {
        return pdf(documentService.contractPdf(id));
    }

    @PostMapping(value = "/contracts/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SalesContractDocument upload(@PathVariable Long id,
                                        @RequestParam(required = false) SalesContractDocument.Kind kind,
                                        @RequestParam("file") MultipartFile file) {
        return contractService.uploadDocument(id, kind, file);
    }

    @GetMapping("/contracts/documents/{documentId}")
    public ResponseEntity<byte[]> download(@PathVariable Long documentId) {
        SalesContractService.StoredFile file = contractService.downloadDocument(documentId);
        return file(file);
    }

    @DeleteMapping("/contracts/documents/{documentId}")
    public ResponseEntity<Void> deleteDocument(@PathVariable Long documentId) {
        contractService.deleteDocument(documentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/contracts/milestones/{milestoneId}/invoice")
    public SalesDtos.ContractView invoiceMilestone(@PathVariable Long milestoneId) {
        return contractService.invoiceMilestone(milestoneId);
    }

    @GetMapping("/events/{eventId}/costs")
    public List<CrmCostItem> eventCosts(@PathVariable Long eventId) {
        return costService.forEvent(eventId);
    }

    @PostMapping("/events/{eventId}/costs")
    public CrmCostItem addEventCost(@PathVariable Long eventId, @RequestBody CrmCostItem item) {
        return costService.addToEvent(eventId, item);
    }

    @GetMapping("/crm/opportunities/{opportunityId}/costs")
    public List<CrmCostItem> opportunityCosts(@PathVariable Long opportunityId) {
        return costService.forOpportunity(opportunityId);
    }

    @PostMapping("/crm/opportunities/{opportunityId}/costs")
    public CrmCostItem addOpportunityCost(@PathVariable Long opportunityId, @RequestBody CrmCostItem item) {
        return costService.addToOpportunity(opportunityId, item);
    }

    @PutMapping("/costs/{costId}")
    public CrmCostItem updateCost(@PathVariable Long costId, @RequestBody CrmCostItem item) {
        return costService.update(costId, item);
    }

    @DeleteMapping("/costs/{costId}")
    public ResponseEntity<Void> deleteCost(@PathVariable Long costId) {
        costService.delete(costId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/events/{eventId}/profitability")
    public SalesDtos.Profitability eventProfitability(@PathVariable Long eventId) {
        return costService.eventProfitability(eventId);
    }

    @GetMapping("/crm/opportunities/{opportunityId}/profitability")
    public SalesDtos.Profitability opportunityProfitability(@PathVariable Long opportunityId) {
        return costService.opportunityProfitability(opportunityId);
    }

    static ResponseEntity<byte[]> pdf(EventDocumentService.PdfDocument pdf) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pdf.fileName() + "\"")
                .body(pdf.content());
    }

    static ResponseEntity<byte[]> file(SalesContractService.StoredFile file) {
        MediaType type = file.contentType() != null ? MediaType.parseMediaType(file.contentType()) : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }
}
