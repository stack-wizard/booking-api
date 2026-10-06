package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.dto.SalesDtos;
import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.SalesContract;
import com.stackwizard.booking_api.model.SalesPaymentMilestone;
import com.stackwizard.booking_api.model.SalesQuote;
import com.stackwizard.booking_api.model.SalesQuoteLine;
import com.stackwizard.booking_api.model.SalesQuoteVersion;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.SalesQuoteLineRepository;
import com.stackwizard.booking_api.repository.SalesQuoteRepository;
import com.stackwizard.booking_api.repository.SalesQuoteVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import static com.stackwizard.booking_api.service.EventDocumentService.esc;

/**
 * Quote and contract PDFs. A sent quote prints its latest version snapshot (what the client received);
 * a draft prints the live lines marked as DRAFT.
 */
@Service
public class SalesDocumentService {
    private final SalesQuoteService quoteService;
    private final SalesContractService contractService;
    private final SalesQuoteRepository quoteRepo;
    private final SalesQuoteLineRepository lineRepo;
    private final SalesQuoteVersionRepository versionRepo;
    private final EventService eventService;
    private final CrmAccountRepository accountRepo;

    public SalesDocumentService(SalesQuoteService quoteService,
                                SalesContractService contractService,
                                SalesQuoteRepository quoteRepo,
                                SalesQuoteLineRepository lineRepo,
                                SalesQuoteVersionRepository versionRepo,
                                EventService eventService,
                                CrmAccountRepository accountRepo) {
        this.quoteService = quoteService;
        this.contractService = contractService;
        this.quoteRepo = quoteRepo;
        this.lineRepo = lineRepo;
        this.versionRepo = versionRepo;
        this.eventService = eventService;
        this.accountRepo = accountRepo;
    }

    @Transactional(readOnly = true)
    public EventDocumentService.PdfDocument quotePdf(Long quoteId) {
        return renderQuote(quoteService.requireQuote(quoteId));
    }

    @Transactional(readOnly = true)
    public EventDocumentService.PdfDocument contractPdf(Long contractId) {
        return renderContract(contractService.view(contractService.requireContract(contractId)));
    }

    EventDocumentService.PdfDocument renderQuote(SalesQuote quote) {
        Optional<SalesQuoteVersion> sent = quote.getVersion() > 0
                ? versionRepo.findByTenantIdAndQuoteIdAndVersion(quote.getTenantId(), quote.getId(), quote.getVersion())
                : Optional.empty();
        String html;
        if (sent.isPresent() && quote.getStatus() != SalesQuote.Status.DRAFT && quote.getStatus() != SalesQuote.Status.APPROVED) {
            html = quoteHtmlFromSnapshot(sent.get().getSnapshot(), quote.getStatus().name());
        } else {
            Event event = eventService.findForSystem(quote.getTenantId(), quote.getEventId()).orElseThrow();
            CrmAccount account = accountRepo.findByIdAndTenantId(quote.getAccountId(), quote.getTenantId()).orElse(null);
            html = quoteHtml(quote, event, account,
                    lineRepo.findByTenantIdAndQuoteIdOrderByDisplayOrderAscIdAsc(quote.getTenantId(), quote.getId()));
        }
        String name = quote.getQuoteNumber() + (quote.getVersion() > 0 ? "-v" + quote.getVersion() : "") + ".pdf";
        return new EventDocumentService.PdfDocument(name, EventDocumentService.renderPdf(html));
    }

    EventDocumentService.PdfDocument renderContract(SalesDtos.ContractView view) {
        SalesContract contract = view.contract();
        SalesQuote quote = quoteRepo.findByIdAndTenantId(contract.getQuoteId(), contract.getTenantId()).orElseThrow();
        Event event = eventService.findForSystem(contract.getTenantId(), contract.getEventId()).orElseThrow();
        CrmAccount account = accountRepo.findByIdAndTenantId(event.getAccountId(), event.getTenantId()).orElse(null);
        List<SalesQuoteLine> lines = lineRepo.findByTenantIdAndQuoteIdOrderByDisplayOrderAscIdAsc(quote.getTenantId(), quote.getId());
        StringBuilder body = new StringBuilder();
        body.append("<h1>Contract ").append(esc(contract.getContractNumber())).append("</h1>");
        body.append("<p class=\"muted\">Status: ").append(contract.getStatus().name())
                .append(" | Quote ").append(esc(quote.getQuoteNumber())).append("</p>");
        body.append("<table class=\"kv\">")
                .append(kv("Client", account != null ? firstNonBlank(account.getLegalName(), account.getName()) : ""))
                .append(kv("Event", event.getName()))
                .append(kv("Dates", event.getDateFrom() + " - " + event.getDateTo()))
                .append(kv("Total", money(contract.getTotalAmount()) + " " + contract.getCurrency()))
                .append("</table>");
        body.append("<h2>Services</h2>").append(linesTable(lines.stream().map(LineRow::of).toList(), contract.getCurrency()));
        body.append("<h2>Payment schedule</h2><table><tr><th>Milestone</th><th>Due</th><th class=\"r\">Amount</th></tr>");
        for (SalesDtos.MilestoneView m : view.milestones()) {
            SalesPaymentMilestone milestone = m.milestone();
            if (milestone.getStatus() == SalesPaymentMilestone.Status.CANCELLED) {
                continue;
            }
            body.append("<tr><td>").append(esc(SalesContractService.milestoneLabel(milestone))).append("</td><td>")
                    .append(milestone.getDueDate()).append("</td><td class=\"r\">").append(money(milestone.getAmount()))
                    .append("</td></tr>");
        }
        body.append("</table>");
        if (contract.getTerms() != null) {
            body.append("<h2>Terms</h2><p class=\"pre\">").append(esc(contract.getTerms())).append("</p>");
        }
        body.append("<table class=\"sign\"><tr><td>For the hotel</td><td>For the client</td></tr>")
                .append("<tr><td class=\"line\"></td><td class=\"line\">")
                .append(contract.getSignedByName() != null ? esc(contract.getSignedByName()) + ", " + contract.getSignedAt().toLocalDate() : "")
                .append("</td></tr></table>");
        return new EventDocumentService.PdfDocument(contract.getContractNumber() + ".pdf",
                EventDocumentService.renderPdf(page("Contract " + contract.getContractNumber(), body.toString())));
    }

    static String quoteHtml(SalesQuote quote, Event event, CrmAccount account, List<SalesQuoteLine> lines) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>Quote ").append(esc(quote.getQuoteNumber())).append("</h1>");
        body.append("<p class=\"muted\">DRAFT - not sent to the client</p>");
        body.append(header(account != null ? firstNonBlank(account.getLegalName(), account.getName()) : "",
                event.getName(), event.getDateFrom() + " - " + event.getDateTo(),
                quote.getValidUntil() != null ? quote.getValidUntil().toString() : null));
        body.append(linesTable(lines.stream().map(LineRow::of).toList(), quote.getCurrency()));
        body.append(totals(quote.getTotalBase(), quote.getTotalOffered(), quote.getTotalTax(), quote.getCurrency()));
        appendText(body, "Notes", quote.getNotes());
        appendText(body, "Terms", quote.getTerms());
        return page("Quote " + quote.getQuoteNumber(), body.toString());
    }

    static String quoteHtmlFromSnapshot(JsonNode s, String status) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>Quote ").append(esc(s.path("quoteNumber").asText())).append("</h1>");
        body.append("<p class=\"muted\">Version ").append(s.path("version").asInt()).append(" | ").append(esc(status)).append("</p>");
        body.append(header(s.path("account").asText(""), s.path("eventName").asText(),
                s.path("dateFrom").asText() + " - " + s.path("dateTo").asText(),
                s.hasNonNull("validUntil") ? s.path("validUntil").asText() : null));
        List<LineRow> rows = new java.util.ArrayList<>();
        for (JsonNode line : s.path("lines")) {
            rows.add(new LineRow(line.path("group").asText(), line.path("description").asText(),
                    line.hasNonNull("serviceDate") ? line.path("serviceDate").asText() : "",
                    line.path("qty").asInt(), line.path("uom").asText(""),
                    line.path("baseRate").decimalValue(), line.path("offeredRate").decimalValue(),
                    line.path("taxPercent").decimalValue(), line.path("amountOffered").decimalValue()));
        }
        String currency = s.path("currency").asText("EUR");
        body.append(linesTable(rows, currency));
        body.append(totals(s.path("totalBase").decimalValue(), s.path("totalOffered").decimalValue(),
                s.path("totalTax").decimalValue(), currency));
        appendText(body, "Notes", s.hasNonNull("notes") ? s.path("notes").asText() : null);
        appendText(body, "Terms", s.hasNonNull("terms") ? s.path("terms").asText() : null);
        return page("Quote " + s.path("quoteNumber").asText(), body.toString());
    }

    record LineRow(String group, String description, String date, int qty, String uom,
                   BigDecimal baseRate, BigDecimal offeredRate, BigDecimal taxPercent, BigDecimal amount) {
        static LineRow of(SalesQuoteLine l) {
            return new LineRow(l.getLineGroup().name(), l.getDescription(),
                    l.getServiceDate() != null ? l.getServiceDate().toString() : "", l.getQty(),
                    l.getUom() != null ? l.getUom() : "", l.getBaseRate(), l.getOfferedRate(),
                    l.getTax1Percent().add(l.getTax2Percent()), l.getAmountOffered());
        }
    }

    private static String header(String client, String event, String dates, String validUntil) {
        return "<table class=\"kv\">" + kv("Client", client) + kv("Event", event) + kv("Dates", dates)
                + (validUntil != null ? kv("Valid until", validUntil) : "") + "</table>";
    }

    private static String linesTable(List<LineRow> rows, String currency) {
        StringBuilder out = new StringBuilder("<table><tr><th>Group</th><th>Service</th><th>Date</th>"
                + "<th class=\"r\">Qty</th><th class=\"r\">List</th><th class=\"r\">Rate</th><th class=\"r\">VAT</th>"
                + "<th class=\"r\">Amount (" + esc(currency) + ")</th></tr>");
        for (LineRow r : rows) {
            out.append("<tr><td>").append(esc(r.group().replace('_', ' '))).append("</td><td>").append(esc(r.description()))
                    .append("</td><td>").append(esc(r.date())).append("</td><td class=\"r\">").append(r.qty()).append(' ')
                    .append(esc(r.uom())).append("</td><td class=\"r\">").append(money(r.baseRate()))
                    .append("</td><td class=\"r\">").append(money(r.offeredRate())).append("</td><td class=\"r\">")
                    .append(r.taxPercent().stripTrailingZeros().toPlainString()).append("%</td><td class=\"r\">")
                    .append(money(r.amount())).append("</td></tr>");
        }
        return out.append("</table>").toString();
    }

    private static String totals(BigDecimal base, BigDecimal offered, BigDecimal tax, String currency) {
        BigDecimal discount = base.subtract(offered);
        return "<table class=\"totals\">"
                + (discount.signum() > 0 ? kv("List total", money(base) + " " + currency) + kv("Discount", "-" + money(discount) + " " + currency) : "")
                + kvRaw("Total incl. VAT", "<b>" + money(offered) + " " + esc(currency) + "</b>")
                + kv("of which VAT", money(tax) + " " + currency)
                + "</table>";
    }

    private static void appendText(StringBuilder body, String title, String text) {
        if (text != null && !text.isBlank()) {
            body.append("<h2>").append(title).append("</h2><p class=\"pre\">").append(esc(text)).append("</p>");
        }
    }

    private static String kv(String key, String value) {
        return kvRaw(key, esc(value));
    }

    private static String kvRaw(String key, String rawValue) {
        return "<tr><th>" + esc(key) + "</th><td>" + rawValue + "</td></tr>";
    }

    private static String page(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><title>" + esc(title) + "</title><style>"
                + "body{font-family:Helvetica,Arial,sans-serif;font-size:10pt;color:#222}"
                + "h1{font-size:16pt;margin:0 0 4px 0}h2{font-size:12pt;margin:16px 0 6px 0}"
                + ".muted{color:#777;margin:0 0 12px 0}table{width:100%;border-collapse:collapse;margin-bottom:8px}"
                + "th,td{text-align:left;padding:4px 6px;border-bottom:1px solid #ddd;vertical-align:top}"
                + "table.kv th{width:25%;color:#555;font-weight:normal}.r{text-align:right}"
                + "table.totals{width:50%;margin-left:50%}table.totals td{text-align:right}"
                + ".pre{white-space:pre-wrap}table.sign td{border:none;width:50%;padding-top:24px}"
                + "table.sign td.line{border-bottom:1px solid #222;height:32px}"
                + "</style></head><body>" + body + "</body></html>";
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
