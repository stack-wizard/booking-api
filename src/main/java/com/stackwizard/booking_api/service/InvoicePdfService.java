package com.stackwizard.booking_api.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceItem;
import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.model.TenantIntegrationConfig;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class InvoicePdfService {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String TEMPLATE_PATH = "templates/invoice-modern-template.html";

    private final InvoiceService invoiceService;
    private final TenantIntegrationConfigService tenantIntegrationConfigService;

    public InvoicePdfService(InvoiceService invoiceService,
                             TenantIntegrationConfigService tenantIntegrationConfigService) {
        this.invoiceService = invoiceService;
        this.tenantIntegrationConfigService = tenantIntegrationConfigService;
    }

    @Transactional(readOnly = true)
    public InvoicePdfDocument generateInvoicePdf(Long invoiceId) {
        Invoice invoice = invoiceService.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));

        List<InvoiceItem> items = invoiceService.findItems(invoice.getId());
        List<InvoicePaymentAllocation> allocations = invoiceService.findAllocations(invoice.getId());
        Optional<TenantIntegrationConfig> fiscalConfig = tenantIntegrationConfigService
                .findByTenantIdAndTypeAndProvider(invoice.getTenantId(), "FISCALIZATION", "OFIS");

        BigDecimal paidAmount = allocations.stream()
                .map(InvoicePaymentAllocation::getAllocatedAmount)
                .map(this::money)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal balanceDue = money(invoice.getTotalGross()).subtract(money(paidAmount));

        try {
            String html = renderHtml(invoice, fiscalConfig.orElse(null), items, allocations, paidAmount, balanceDue);
            byte[] pdfBytes = convertHtmlToPdf(html);
            String invoiceNo = firstNonBlank(invoice.getInvoiceNumber(), "invoice-" + invoice.getId());
            return new InvoicePdfDocument(sanitizeFileName(invoiceNo) + ".pdf", pdfBytes);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException(
                    "Failed to generate invoice PDF from HTML template: " + rootCauseMessage(ex),
                    ex
            );
        }
    }

    private String renderHtml(Invoice invoice,
                              TenantIntegrationConfig fiscalConfig,
                              List<InvoiceItem> items,
                              List<InvoicePaymentAllocation> allocations,
                              BigDecimal paidAmount,
                              BigDecimal balanceDue) throws IOException {
        String template = new ClassPathResource(TEMPLATE_PATH).getContentAsString(StandardCharsets.UTF_8);
        String currency = firstNonBlank(invoice.getCurrency(), "EUR");

        String sellerName = firstNonBlank(
                fiscalConfig != null ? fiscalConfig.getHotelName() : null,
                fiscalConfig != null ? fiscalConfig.getLegalOwner() : null,
                "Tenant " + invoice.getTenantId()
        );

        String qrLink = buildQrLink(invoice.getFiscalQrUrl());
        String itemRows = buildItemRows(items, currency);
        String taxRows = buildTaxRows(items, currency);
        String paymentRows = buildPaymentRows(allocations, currency);

        Map<String, String> values = new LinkedHashMap<>();
        values.put("INVOICE_NUMBER", esc(firstNonBlank(invoice.getInvoiceNumber(), "-")));
        values.put("INVOICE_DATE", esc(formatDate(invoice.getInvoiceDate())));
        values.put("INVOICE_TYPE", esc(invoice.getInvoiceType() != null ? invoice.getInvoiceType().name() : "-"));
        values.put("INVOICE_STATUS", esc(invoice.getStatus() != null ? invoice.getStatus().name() : "-"));
        values.put("PAYMENT_STATUS", esc(firstNonBlank(invoice.getPaymentStatus(), "-")));
        values.put("FISCALIZATION_STATUS", esc(invoice.getFiscalizationStatus() != null ? invoice.getFiscalizationStatus().name() : "-"));
        values.put("ISSUED_BY_MODE", esc(invoice.getIssuedByMode() != null ? invoice.getIssuedByMode().name() : "-"));

        values.put("SELLER_NAME", esc(sellerName));
        values.put("SELLER_OWNER", esc(firstNonBlank(fiscalConfig != null ? fiscalConfig.getLegalOwner() : null, "-")));
        values.put("SELLER_TAX_ID", esc(firstNonBlank(fiscalConfig != null ? fiscalConfig.getPropertyTaxNumber() : null, "-")));
        values.put("SELLER_COUNTRY", esc(firstNonBlank(
                fiscalConfig != null ? fiscalConfig.getCountryName() : null,
                fiscalConfig != null ? fiscalConfig.getCountryCode() : null,
                "-"
        )));
        values.put("SELLER_WEBSITE", esc(firstNonBlank(fiscalConfig != null ? fiscalConfig.getBaseUrl() : null, "-")));

        values.put("CUSTOMER_NAME", esc(firstNonBlank(invoice.getCustomerName(), "-")));
        values.put("CUSTOMER_EMAIL", esc(firstNonBlank(invoice.getCustomerEmail(), "-")));
        values.put("CUSTOMER_PHONE", esc(firstNonBlank(invoice.getCustomerPhone(), "-")));

        values.put("REFERENCE_TABLE", esc(firstNonBlank(invoice.getReferenceTable(), "-")));
        values.put("REFERENCE_ID", esc(invoice.getReferenceId() != null ? invoice.getReferenceId().toString() : "-"));
        values.put("RESERVATION_REQUEST_ID", esc(invoice.getReservationRequestId() != null ? invoice.getReservationRequestId().toString() : "-"));

        values.put("CURRENCY", esc(currency));
        values.put("SUBTOTAL_NET", esc(formatAmount(invoice.getSubtotalNet())));
        values.put("DISCOUNT_TOTAL", esc(formatAmount(invoice.getDiscountTotal())));
        values.put("TAX1_TOTAL", esc(formatAmount(invoice.getTax1Total())));
        values.put("TAX2_TOTAL", esc(formatAmount(invoice.getTax2Total())));
        values.put("TOTAL_GROSS", esc(formatAmount(invoice.getTotalGross())));
        values.put("PAID_AMOUNT", esc(formatAmount(paidAmount)));
        values.put("BALANCE_DUE", esc(formatAmount(balanceDue)));

        values.put("FISCALIZED_AT", esc(formatDateTime(invoice.getFiscalizedAt())));
        values.put("FISCAL_FOLIO_NO", esc(firstNonBlank(invoice.getFiscalFolioNo(), "-")));
        values.put("FISCAL_DOCUMENT_NO_1", esc(firstNonBlank(invoice.getFiscalDocumentNo1(), "-")));
        values.put("FISCAL_DOCUMENT_NO_2", esc(firstNonBlank(invoice.getFiscalDocumentNo2(), "-")));
        values.put("FISCAL_SPECIAL_ID", esc(firstNonBlank(invoice.getFiscalSpecialId(), "-")));
        values.put("FISCAL_QR_LINK", qrLink);
        values.put("FISCAL_ERROR", esc(firstNonBlank(invoice.getFiscalErrorMessage(), "-")));

        values.put("CREATED_AT", esc(formatDateTime(invoice.getCreatedAt())));
        values.put("ISSUED_AT", esc(formatDateTime(invoice.getIssuedAt())));

        values.put("ITEM_ROWS", itemRows);
        values.put("TAX_ROWS", taxRows);
        values.put("PAYMENT_ROWS", paymentRows);

        return applyTemplate(template, values);
    }

    private byte[] convertHtmlToPdf(String html) {
        return convertHtmlToPdfWithOpenHtmlToPdf(html);
    }

    private byte[] convertHtmlToPdfWithOpenHtmlToPdf(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("openhtmltopdf rendering failed: " + rootCauseMessage(ex), ex);
        }
    }

    private String buildItemRows(List<InvoiceItem> items, String currency) {
        if (items == null || items.isEmpty()) {
            return "<tr><td colspan=\"9\" class=\"empty\">No line items</td></tr>";
        }

        StringBuilder rows = new StringBuilder();
        for (InvoiceItem item : items) {
            int qty = item.getQuantity() == null || item.getQuantity() <= 0 ? 1 : item.getQuantity();
            BigDecimal totalNet = money(item.getPriceWithoutTax());
            BigDecimal totalTax = money(item.getTax1Amount()).add(money(item.getTax2Amount()));
            BigDecimal totalGross = money(item.getGrossAmount());
            BigDecimal unitNet = totalNet.divide(BigDecimal.valueOf(qty), 2, RoundingMode.HALF_UP);
            BigDecimal unitGross = money(item.getUnitPriceGross());

            String taxRate = buildTaxRateLabel(item);
            rows.append("<tr>")
                    .append("<td>").append(esc(item.getLineNo() != null ? String.valueOf(item.getLineNo()) : "-")).append("</td>")
                    .append("<td class=\"text-left\">").append(esc(firstNonBlank(item.getProductName(), "-"))).append("</td>")
                    .append("<td>").append(esc(String.valueOf(qty))).append("</td>")
                    .append("<td class=\"text-right\">").append(esc(formatAmount(unitNet))).append("</td>")
                    .append("<td>").append(esc(taxRate)).append("</td>")
                    .append("<td class=\"text-right\">").append(esc(formatAmount(totalTax))).append("</td>")
                    .append("<td class=\"text-right\">").append(esc(formatAmount(unitGross))).append("</td>")
                    .append("<td class=\"text-right\">").append(esc(formatAmount(totalGross))).append("</td>")
                    .append("<td>").append(esc(currency)).append("</td>")
                    .append("</tr>");
        }
        return rows.toString();
    }

    private String buildTaxRows(List<InvoiceItem> items, String currency) {
        List<TaxRow> taxRows = toTaxRows(items);
        if (taxRows.isEmpty()) {
            return "<tr><td colspan=\"6\" class=\"empty\">No tax lines</td></tr>";
        }

        StringBuilder rows = new StringBuilder();
        for (TaxRow row : taxRows) {
            rows.append("<tr>")
                    .append("<td>").append(esc(row.taxName())).append("</td>")
                    .append("<td>").append(esc(row.rateLabel())).append("</td>")
                    .append("<td class=\"text-right\">").append(esc(formatAmount(row.baseAmount()))).append("</td>")
                    .append("<td class=\"text-right\">").append(esc(formatAmount(row.taxAmount()))).append("</td>")
                    .append("<td class=\"text-right\">").append(esc(formatAmount(row.grossAmount()))).append("</td>")
                    .append("<td>").append(esc(currency)).append("</td>")
                    .append("</tr>");
        }
        return rows.toString();
    }

    private List<TaxRow> toTaxRows(List<InvoiceItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<String, TaxAccumulator> buckets = new LinkedHashMap<>();
        for (InvoiceItem item : items) {
            addTaxBucket(buckets, "Tax1", item.getTax1Percent(), item.getPriceWithoutTax(), item.getTax1Amount());
            addTaxBucket(buckets, "Tax2", item.getTax2Percent(), item.getPriceWithoutTax(), item.getTax2Amount());
        }

        return buckets.values().stream()
                .sorted(Comparator.comparing(TaxAccumulator::key))
                .map(v -> new TaxRow(
                        v.taxName,
                        formatPercent(v.rate) + "%",
                        v.baseAmount,
                        v.taxAmount,
                        v.baseAmount.add(v.taxAmount)
                ))
                .toList();
    }

    private void addTaxBucket(Map<String, TaxAccumulator> buckets,
                              String taxName,
                              BigDecimal rate,
                              BigDecimal base,
                              BigDecimal taxAmount) {
        BigDecimal normalizedRate = percentScale(rate);
        BigDecimal normalizedTax = money(taxAmount);
        if (normalizedTax.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        String key = taxName + "|" + normalizedRate.toPlainString();
        TaxAccumulator bucket = buckets.computeIfAbsent(
                key,
                k -> new TaxAccumulator(key, taxName, normalizedRate)
        );
        bucket.baseAmount = bucket.baseAmount.add(money(base));
        bucket.taxAmount = bucket.taxAmount.add(normalizedTax);
    }

    private String buildPaymentRows(List<InvoicePaymentAllocation> allocations, String defaultCurrency) {
        if (allocations == null || allocations.isEmpty()) {
            return "<tr><td colspan=\"7\" class=\"empty\">No payments allocated</td></tr>";
        }

        StringBuilder rows = new StringBuilder();
        for (InvoicePaymentAllocation allocation : allocations) {
            PaymentTransaction tx = allocation.getPaymentTransaction();
            String paymentType = tx != null ? firstNonBlank(tx.getPaymentType(), "-") : "-";
            String status = tx != null ? firstNonBlank(tx.getStatus(), "-") : "-";
            String currency = tx != null ? firstNonBlank(tx.getCurrency(), defaultCurrency) : defaultCurrency;
            String externalRef = tx != null
                    ? firstNonBlank(tx.getExternalRef(), tx.getNote(), "-")
                    : firstNonBlank(stringValue(allocation.getPaymentTransactionId()), "-");
            String created = formatDateTime(tx != null ? tx.getCreatedAt() : allocation.getCreatedAt());

            rows.append("<tr>")
                    .append("<td>").append(esc(stringValue(allocation.getPaymentTransactionId()))).append("</td>")
                    .append("<td>").append(esc(paymentType)).append("</td>")
                    .append("<td>").append(esc(status)).append("</td>")
                    .append("<td class=\"text-left\">").append(esc(externalRef)).append("</td>")
                    .append("<td>").append(esc(created)).append("</td>")
                    .append("<td class=\"text-right\">").append(esc(formatAmount(allocation.getAllocatedAmount()))).append("</td>")
                    .append("<td>").append(esc(firstNonBlank(currency, "-"))).append("</td>")
                    .append("</tr>");
        }
        return rows.toString();
    }

    private String buildTaxRateLabel(InvoiceItem item) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        BigDecimal tax1 = percentScale(item.getTax1Percent());
        BigDecimal tax2 = percentScale(item.getTax2Percent());
        if (tax1.compareTo(BigDecimal.ZERO) > 0) {
            parts.add(formatPercent(tax1) + "%");
        }
        if (tax2.compareTo(BigDecimal.ZERO) > 0) {
            parts.add(formatPercent(tax2) + "%");
        }
        return parts.isEmpty() ? "0.00%" : String.join(" + ", parts);
    }

    private String buildQrLink(String qrUrl) {
        String normalized = firstNonBlank(qrUrl);
        if (normalized == null) {
            return "-";
        }
        String escaped = esc(normalized);
        return "<a href=\"" + escaped + "\" target=\"_blank\">" + escaped + "</a>";
    }

    private String applyTemplate(String template, Map<String, String> values) {
        String output = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            output = output.replace(token(entry.getKey()), firstNonBlank(entry.getValue(), "-"));
        }
        return output;
    }

    private String token(String key) {
        return "[[" + key + "]]";
    }

    private String sanitizeFileName(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private String formatAmount(BigDecimal value) {
        return money(value).toPlainString();
    }

    private String formatPercent(BigDecimal value) {
        return percentScale(value).toPlainString();
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal percentScale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private String formatDate(LocalDate value) {
        return value == null ? "-" : DATE_FORMAT.format(value);
    }

    private String formatDateTime(OffsetDateTime value) {
        return value == null ? "-" : DATE_TIME_FORMAT.format(value);
    }

    private String stringValue(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String esc(String value) {
        String raw = value == null ? "" : value;
        return raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            return cursor.getClass().getSimpleName();
        }
        return message;
    }

    private static class TaxAccumulator {
        private final String key;
        private final String taxName;
        private final BigDecimal rate;
        private BigDecimal baseAmount;
        private BigDecimal taxAmount;

        private TaxAccumulator(String key, String taxName, BigDecimal rate) {
            this.key = key;
            this.taxName = taxName;
            this.rate = rate;
            this.baseAmount = BigDecimal.ZERO;
            this.taxAmount = BigDecimal.ZERO;
        }

        private String key() {
            return key;
        }
    }

    private record TaxRow(String taxName,
                          String rateLabel,
                          BigDecimal baseAmount,
                          BigDecimal taxAmount,
                          BigDecimal grossAmount) {
    }

    public record InvoicePdfDocument(String fileName, byte[] content) {
    }
}
