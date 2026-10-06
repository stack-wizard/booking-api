package com.stackwizard.booking_api.dto;

import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.SalesContract;
import com.stackwizard.booking_api.model.SalesContractDocument;
import com.stackwizard.booking_api.model.SalesPaymentMilestone;
import com.stackwizard.booking_api.model.SalesQuote;
import com.stackwizard.booking_api.model.SalesQuoteApproval;
import com.stackwizard.booking_api.model.SalesQuoteLine;
import com.stackwizard.booking_api.model.SalesQuoteVersion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class SalesDtos {
    private SalesDtos() {
    }

    public record QuoteHeaderRequest(LocalDate validUntil, String notes, String terms) {
    }

    public record QuoteLineRequest(SalesQuoteLine.Group lineGroup,
                                   Long productId,
                                   String description,
                                   LocalDate serviceDate,
                                   String uom,
                                   Integer qty,
                                   BigDecimal baseRate,
                                   BigDecimal offeredRate,
                                   BigDecimal tax1Percent,
                                   BigDecimal tax2Percent) {
    }

    public record NoteRequest(String note) {
    }

    public record DecisionRequest(String name, String note) {
    }

    public record VersionSummary(Integer version, BigDecimal totalOffered, Long sentBy, OffsetDateTime createdAt) {
        public static VersionSummary of(SalesQuoteVersion v) {
            return new VersionSummary(v.getVersion(), v.getTotalOffered(), v.getSentBy(), v.getCreatedAt());
        }
    }

    public record QuoteView(SalesQuote quote,
                            List<SalesQuoteLine> lines,
                            List<GroupTotal> groups,
                            List<VersionSummary> versions,
                            List<SalesQuoteApproval> approvals,
                            BigDecimal approvalThresholdPercent,
                            boolean needsApproval,
                            String portalUrl) {
    }

    public record GroupTotal(SalesQuoteLine.Group group, BigDecimal amountBase, BigDecimal amountOffered) {
    }

    public record ContractRequest(String terms) {
    }

    public record SignRequest(String signedByName, OffsetDateTime signedAt) {
    }

    public record MilestoneRequest(SalesPaymentMilestone.Kind kind,
                                   String label,
                                   LocalDate dueDate,
                                   BigDecimal percent,
                                   BigDecimal amount) {
    }

    public record MilestoneView(SalesPaymentMilestone milestone,
                                String invoiceNumber,
                                String invoiceStatus,
                                String paymentStatus,
                                BigDecimal invoiceTotal) {
        public static MilestoneView of(SalesPaymentMilestone m, Invoice invoice) {
            return new MilestoneView(m,
                    invoice != null ? invoice.getInvoiceNumber() : null,
                    invoice != null && invoice.getStatus() != null ? invoice.getStatus().name() : null,
                    invoice != null ? invoice.getPaymentStatus() : null,
                    invoice != null ? invoice.getTotalGross() : null);
        }
    }

    public record ContractView(SalesContract contract,
                               String quoteNumber,
                               List<MilestoneView> milestones,
                               List<SalesContractDocument> documents) {
    }

    public record Profitability(String currency,
                                BigDecimal revenue,
                                BigDecimal quotedTotal,
                                BigDecimal itemCost,
                                BigDecimal extraCost,
                                BigDecimal margin,
                                BigDecimal marginPercent,
                                List<EventProfit> events) {
    }

    public record EventProfit(Long eventId,
                              String name,
                              String status,
                              BigDecimal revenue,
                              BigDecimal quotedTotal,
                              BigDecimal itemCost,
                              BigDecimal extraCost,
                              BigDecimal margin) {
    }
}
