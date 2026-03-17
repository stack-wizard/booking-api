package com.stackwizard.booking_api.service.fiscal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.dto.InvoiceFiscalizeRequest;
import com.stackwizard.booking_api.dto.InvoiceIssueRequest;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.model.FiscalCashRegister;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceFiscalizationStatus;
import com.stackwizard.booking_api.model.InvoiceItem;
import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.model.IssuedByMode;
import com.stackwizard.booking_api.model.OperaFiscalChargeMapping;
import com.stackwizard.booking_api.model.OperaFiscalPaymentMapping;
import com.stackwizard.booking_api.model.OperaFiscalTaxMapping;
import com.stackwizard.booking_api.model.OperaFiscalUdfMapping;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.TenantIntegrationConfig;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.repository.InvoiceItemRepository;
import com.stackwizard.booking_api.repository.InvoicePaymentAllocationRepository;
import com.stackwizard.booking_api.repository.InvoiceRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.service.FiscalCashRegisterService;
import com.stackwizard.booking_api.service.InvoiceService;
import com.stackwizard.booking_api.service.PaymentTransactionService;
import com.stackwizard.booking_api.service.TenantIntegrationConfigService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class InvoiceFiscalizationService {
    private static final String REFERENCE_TABLE_INVOICE = "invoice";
    private static final String DEFAULT_OFIS_FOLIO_TYPE = "FISCAL";
    private static final String DEFAULT_OFIS_APPLICATION = "BookingAPI";
    private static final String DEFAULT_OFIS_DOCUMENT_INVOICE = "INVOICE";
    private static final String DEFAULT_OFIS_DOCUMENT_CREDIT_NOTE = "CREDIT_NOTE";
    private static final String DEFAULT_OFIS_TIMEOUT = "30";
    private static final String DEFAULT_OFIS_CHARGE_TRX_CODE = "3100";
    private static final String DEFAULT_OFIS_TAX1_TRX_CODE = "3";
    private static final String DEFAULT_OFIS_TAX2_TRX_CODE = "4";
    private static final String DEFAULT_OFIS_CASH_PAYMENT_TRX_CODE = "9001";
    private static final String DEFAULT_OFIS_CARD_PAYMENT_TRX_CODE = "9002";
    private static final String DEFAULT_OFIS_BANK_PAYMENT_TRX_CODE = "9003";
    private static final String DEFAULT_OFIS_ROOM_CHARGE_PAYMENT_TRX_CODE = "9004";
    private static final String INTEGRATION_TYPE_FISCALIZATION = "FISCALIZATION";
    private static final String INTEGRATION_PROVIDER_OFIS = "OFIS";
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final InvoiceRepository invoiceRepo;
    private final InvoiceItemRepository invoiceItemRepo;
    private final InvoicePaymentAllocationRepository allocationRepo;
    private final ReservationRequestRepository requestRepo;
    private final ProductRepository productRepo;
    private final AppUserRepository appUserRepo;
    private final PaymentTransactionService paymentTransactionService;
    private final InvoiceService invoiceService;
    private final FiscalCashRegisterService fiscalCashRegisterService;
    private final TenantIntegrationConfigService tenantIntegrationConfigService;
    private final OfisFiscalizationClient ofisFiscalizationClient;
    private final OperaFiscalMappingService operaFiscalMappingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InvoiceFiscalizationService(InvoiceRepository invoiceRepo,
                                       InvoiceItemRepository invoiceItemRepo,
                                       InvoicePaymentAllocationRepository allocationRepo,
                                       ReservationRequestRepository requestRepo,
                                       ProductRepository productRepo,
                                       AppUserRepository appUserRepo,
                                       PaymentTransactionService paymentTransactionService,
                                       InvoiceService invoiceService,
                                       FiscalCashRegisterService fiscalCashRegisterService,
                                       TenantIntegrationConfigService tenantIntegrationConfigService,
                                       OfisFiscalizationClient ofisFiscalizationClient,
                                       OperaFiscalMappingService operaFiscalMappingService) {
        this.invoiceRepo = invoiceRepo;
        this.invoiceItemRepo = invoiceItemRepo;
        this.allocationRepo = allocationRepo;
        this.requestRepo = requestRepo;
        this.productRepo = productRepo;
        this.appUserRepo = appUserRepo;
        this.paymentTransactionService = paymentTransactionService;
        this.invoiceService = invoiceService;
        this.fiscalCashRegisterService = fiscalCashRegisterService;
        this.tenantIntegrationConfigService = tenantIntegrationConfigService;
        this.ofisFiscalizationClient = ofisFiscalizationClient;
        this.operaFiscalMappingService = operaFiscalMappingService;
    }

    @Transactional(noRollbackFor = RuntimeException.class)
    public Invoice fiscalizeInvoice(Long invoiceId, InvoiceFiscalizeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Invoice sourceInvoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        Invoice invoice = sourceInvoice;
        try {
            invoice = ensureIssuedForFiscalization(sourceInvoice, request);
            List<InvoiceItem> items = invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(invoice.getId());
            List<InvoicePaymentAllocation> allocations = allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(invoice.getId());
            ReservationRequest reservationRequest = resolveReservationRequest(invoice);
            Map<Long, Product> productsById = loadProductsById(items);

            JsonNode payload = request.getOfisPayload();
            if (payload == null || payload.isNull()) {
                payload = buildOfisPayload(invoice, items, allocations, reservationRequest, productsById, request);
            } else if (!payload.isObject()) {
                throw new IllegalArgumentException("ofisPayload must be a JSON object");
            }

            invoice.setFiscalLastRequestPayload(payload);
            invoice.setFiscalErrorMessage(null);
            invoiceRepo.save(invoice);

            JsonNode response = ofisFiscalizationClient.fiscalize(invoice.getTenantId(), payload);
            applyFiscalResponse(invoice, response);
            invoice.setFiscalizationStatus(InvoiceFiscalizationStatus.FISCALIZED);
            if (invoice.getFiscalizedAt() == null) {
                invoice.setFiscalizedAt(OffsetDateTime.now());
            }
            return invoiceRepo.save(invoice);
        } catch (RuntimeException ex) {
            invoice.setFiscalizationStatus(InvoiceFiscalizationStatus.FAILED);
            String message = ex.getMessage();
            if (message != null && message.length() > 3000) {
                message = message.substring(0, 3000);
            }
            invoice.setFiscalErrorMessage(message);
            invoiceRepo.save(invoice);
            throw ex;
        }
    }

    @Transactional
    public JsonNode buildFiscalPayload(Long invoiceId, InvoiceFiscalizeRequest request) {
        InvoiceFiscalizeRequest effectiveRequest = request != null ? request : new InvoiceFiscalizeRequest();
        Invoice sourceInvoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));

        Invoice invoice = ensureIssuedForFiscalization(sourceInvoice, effectiveRequest);
        List<InvoiceItem> items = invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(invoice.getId());
        List<InvoicePaymentAllocation> allocations = allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(invoice.getId());
        ReservationRequest reservationRequest = resolveReservationRequest(invoice);
        Map<Long, Product> productsById = loadProductsById(items);

        JsonNode payload = effectiveRequest.getOfisPayload();
        if (payload == null || payload.isNull()) {
            payload = buildOfisPayload(invoice, items, allocations, reservationRequest, productsById, effectiveRequest);
        } else if (!payload.isObject()) {
            throw new IllegalArgumentException("ofisPayload must be a JSON object");
        }
        return payload;
    }

    private Map<Long, Product> loadProductsById(List<InvoiceItem> items) {
        List<Long> productIds = items.stream()
                .map(InvoiceItem::getProductId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<Long, Product> productById = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (Product product : productRepo.findAllById(productIds)) {
                productById.put(product.getId(), product);
            }
        }
        return productById;
    }

    private Invoice ensureIssuedForFiscalization(Invoice invoice, InvoiceFiscalizeRequest request) {
        Long businessPremiseId = invoice.getBusinessPremiseId() != null
                ? invoice.getBusinessPremiseId()
                : request.getBusinessPremiseId();
        Long cashRegisterId = invoice.getCashRegisterId() != null
                ? invoice.getCashRegisterId()
                : request.getCashRegisterId();

        if (cashRegisterId != null && businessPremiseId == null) {
            FiscalCashRegister register = fiscalCashRegisterService.requireByIdAndTenantId(cashRegisterId, invoice.getTenantId());
            if (!Boolean.TRUE.equals(register.getActive())) {
                throw new IllegalStateException("Fiscal cash register is inactive");
            }
            businessPremiseId = register.getBusinessPremiseId();
        }

        if (businessPremiseId != null && cashRegisterId == null) {
            List<FiscalCashRegister> premiseRegisters = fiscalCashRegisterService.findByBusinessPremiseId(businessPremiseId).stream()
                    .filter(r -> invoice.getTenantId().equals(r.getTenantId()))
                    .filter(r -> Boolean.TRUE.equals(r.getActive()))
                    .toList();
            if (premiseRegisters.size() == 1) {
                cashRegisterId = premiseRegisters.get(0).getId();
            } else if (premiseRegisters.isEmpty()) {
                throw new IllegalArgumentException("No active cashRegister found for selected businessPremiseId");
            } else {
                throw new IllegalArgumentException("Multiple active cashRegister entries found for businessPremiseId; pass cashRegisterId explicitly");
            }
        }

        if (businessPremiseId == null && cashRegisterId == null) {
            List<FiscalCashRegister> tenantRegisters = fiscalCashRegisterService.findByTenantId(invoice.getTenantId()).stream()
                    .filter(r -> Boolean.TRUE.equals(r.getActive()))
                    .toList();
            if (tenantRegisters.size() == 1) {
                FiscalCashRegister resolved = tenantRegisters.get(0);
                cashRegisterId = resolved.getId();
                businessPremiseId = resolved.getBusinessPremiseId();
            } else if (tenantRegisters.isEmpty()) {
                throw new IllegalArgumentException("businessPremiseId and cashRegisterId are required for fiscalization; no active fiscal cash register configured");
            } else {
                throw new IllegalArgumentException("businessPremiseId and cashRegisterId are required for fiscalization; multiple active fiscal cash registers found");
            }
        }

        if (businessPremiseId == null || cashRegisterId == null) {
            throw new IllegalArgumentException("businessPremiseId and cashRegisterId are required for fiscalization");
        }

        InvoiceIssueRequest issueRequest = new InvoiceIssueRequest();
        issueRequest.setBusinessPremiseId(businessPremiseId);
        issueRequest.setCashRegisterId(cashRegisterId);
        issueRequest.setIssuedByMode(firstNonNull(request.getIssuedByMode(), invoice.getIssuedByMode()));
        issueRequest.setIssuedByUserId(request.getIssuedByUserId());
        return invoiceService.issueInvoice(invoice.getId(), issueRequest);
    }

    private ReservationRequest resolveReservationRequest(Invoice invoice) {
        if (invoice.getReservationRequestId() == null) {
            return null;
        }
        return requestRepo.findById(invoice.getReservationRequestId()).orElse(null);
    }

    private JsonNode buildOfisPayload(Invoice invoice,
                                      List<InvoiceItem> items,
                                      List<InvoicePaymentAllocation> allocations,
                                      ReservationRequest reservationRequest,
                                      Map<Long, Product> productsById,
                                      InvoiceFiscalizeRequest request) {
        OffsetDateTime issuedAt = invoice.getIssuedAt() != null ? invoice.getIssuedAt() : OffsetDateTime.now();
        String billNo = invoice.getInvoiceNumber();
        String businessDate = invoice.getInvoiceDate() != null
                ? invoice.getInvoiceDate().toString()
                : issuedAt.toLocalDate().toString();
        String businessDateTime = issuedAt.toLocalDateTime().format(DATE_TIME_FORMATTER);
        boolean creditBill = isStornoType(invoice.getInvoiceType()) || zeroSafe(invoice.getTotalGross()).compareTo(BigDecimal.ZERO) < 0;
        String defaultDocumentType = creditBill ? DEFAULT_OFIS_DOCUMENT_CREDIT_NOTE : DEFAULT_OFIS_DOCUMENT_INVOICE;
        TenantIntegrationConfig fiscalConfig = tenantIntegrationConfigService
                .findByTenantIdAndTypeAndProvider(invoice.getTenantId(), INTEGRATION_TYPE_FISCALIZATION, INTEGRATION_PROVIDER_OFIS)
                .orElse(null);

        String hotelCode = requireRequestValue(
                firstNonBlank(
                        normalizeNullable(request.getHotelCode()),
                        fiscalConfig != null ? normalizeNullable(fiscalConfig.getHotelCode()) : null
                ),
                "hotelCode"
        );
        String propertyTaxNumber = requireRequestValue(
                firstNonBlank(
                        normalizeNullable(request.getPropertyTaxNumber()),
                        fiscalConfig != null ? normalizeNullable(fiscalConfig.getPropertyTaxNumber()) : null
                ),
                "propertyTaxNumber"
        );
        String countryCode = firstNonBlank(
                normalizeNullable(request.getCountryCode()),
                fiscalConfig != null ? normalizeNullable(fiscalConfig.getCountryCode()) : null,
                "HR"
        );
        String countryName = firstNonBlank(
                normalizeNullable(request.getCountryName()),
                fiscalConfig != null ? normalizeNullable(fiscalConfig.getCountryName()) : null,
                "Croatia"
        );
        String application = firstNonBlank(
                normalizeNullable(request.getApplication()),
                fiscalConfig != null ? normalizeNullable(fiscalConfig.getApplicationName()) : null,
                DEFAULT_OFIS_APPLICATION
        );
        String command = firstNonBlank(normalizeNullable(request.getCommand()), defaultDocumentType);
        String documentType = firstNonBlank(normalizeNullable(request.getDocumentType()), defaultDocumentType);
        String fiscalTimeout = firstNonBlank(normalizeNullable(request.getFiscalTimeoutPeriod()), DEFAULT_OFIS_TIMEOUT);
        String window = firstNonBlank(normalizeNullable(request.getWindow()), "1");
        String cashierNumber = firstNonBlank(normalizeNullable(request.getCashierNumber()), "1");
        String cashierId = normalizeNullable(request.getCashierId());
        String fiscalFolioStatus = firstNonBlank(normalizeNullable(request.getFiscalFolioStatus()), "OK");
        String requestDefaultChargeTrxCode = firstNonBlank(normalizeNullable(request.getDefaultChargeTrxCode()), DEFAULT_OFIS_CHARGE_TRX_CODE);
        String operaFiscalBillNo = firstNonBlank(normalizeNullable(request.getOperaFiscalBillNo()), invoice.getInvoiceNumber());

        String terminalId = firstNonBlank(
                normalizeNullable(request.getTerminalId()),
                normalizeNullable(fiscalCashRegisterService.requireByIdAndTenantId(invoice.getCashRegisterId(), invoice.getTenantId()).getTerminalId())
        );
        String terminalAddressAndPort = firstNonBlank(normalizeNullable(request.getTerminalAddessAndPort()), "");
        String terminalCode = normalizeNullable(invoice.getCashRegisterCodeSnapshot());
        if (!StringUtils.hasText(terminalCode)) {
            terminalCode = String.valueOf(invoice.getCashRegisterId());
        }
        String businessPremiseCode = normalizeNullable(invoice.getBusinessPremiseCodeSnapshot());
        if (!StringUtils.hasText(businessPremiseCode)) {
            businessPremiseCode = "";
        }

        Map<String, Object> lastSupportingDocumentInfo = buildLastSupportingDocumentInfo(invoice);
        List<Map<String, Object>> postings = new ArrayList<>();
        List<Map<String, Object>> trxInfo = new ArrayList<>();
        List<Map<String, Object>> taxes = new ArrayList<>();
        Map<String, TaxSummary> taxSummaryByKey = new LinkedHashMap<>();
        Map<String, BucketSummary> bucketSummaryByKey = new LinkedHashMap<>();
        BigDecimal netAmountTotal = BigDecimal.ZERO;
        BigDecimal grossAmountTotal = BigDecimal.ZERO;

        for (InvoiceItem item : items) {
            Product product = item.getProductId() != null ? productsById.get(item.getProductId()) : null;
            Optional<OperaFiscalChargeMapping> chargeMappingOpt = operaFiscalMappingService.resolveChargeMapping(
                    invoice.getTenantId(),
                    item.getProductId(),
                    product != null ? product.getProductType() : null
            );
            OperaFiscalChargeMapping chargeMapping = chargeMappingOpt.orElse(null);

            String chargeTrxCode = chargeMapping != null && StringUtils.hasText(chargeMapping.getTrxCode())
                    ? chargeMapping.getTrxCode()
                    : requestDefaultChargeTrxCode;
            String chargeTrxType = chargeMapping != null && StringUtils.hasText(chargeMapping.getTrxType())
                    ? chargeMapping.getTrxType()
                    : "C";
            String chargeTrxCodeType = chargeMapping != null && StringUtils.hasText(chargeMapping.getTrxCodeType())
                    ? chargeMapping.getTrxCodeType()
                    : "L";
            String chargeDescription = firstNonBlank(
                    chargeMapping != null ? normalizeNullable(chargeMapping.getDescription()) : null,
                    item.getProductName()
            );
            String chargeGroup = chargeMapping != null ? normalizeNullable(chargeMapping.getTrxGroup()) : null;
            String chargeSubGroup = chargeMapping != null ? normalizeNullable(chargeMapping.getTrxSubGroup()) : null;

            BigDecimal quantity = BigDecimal.valueOf(item.getQuantity() != null ? item.getQuantity() : 1L);
            if (quantity.compareTo(BigDecimal.ZERO) == 0) {
                quantity = BigDecimal.ONE;
            }
            BigDecimal grossAmount = money(zeroSafe(item.getGrossAmount()));
            BigDecimal unitPrice = grossAmount.divide(quantity, 6, RoundingMode.HALF_UP);
            BigDecimal netAmount = money(zeroSafe(item.getNettPrice() != null ? item.getNettPrice() : item.getPriceWithoutTax()));
            netAmountTotal = netAmountTotal.add(netAmount.abs());
            grossAmountTotal = grossAmountTotal.add(grossAmount.abs());
            BigDecimal tax1Amount = resolveTaxAmount(item.getTax1Amount(), item.getTax1Percent(), netAmount, grossAmount);
            BigDecimal tax2Amount = resolveTaxAmount(item.getTax2Amount(), item.getTax2Percent(), netAmount, grossAmount);

            List<Map<String, Object>> generates = new ArrayList<>();
            addTaxGenerate(generates, taxSummaryByKey, invoice.getTenantId(), request.getTax1TrxCode(), DEFAULT_OFIS_TAX1_TRX_CODE,
                    item.getTax1Percent(), tax1Amount, quantity, businessDate, businessDateTime, invoice.getCurrency());
            addTaxGenerate(generates, taxSummaryByKey, invoice.getTenantId(), request.getTax2TrxCode(), DEFAULT_OFIS_TAX2_TRX_CODE,
                    item.getTax2Percent(), tax2Amount, quantity, businessDate, businessDateTime, invoice.getCurrency());

            BigDecimal debit = grossAmount.signum() >= 0 ? grossAmount.abs() : BigDecimal.ZERO;
            BigDecimal credit = grossAmount.signum() < 0 ? grossAmount.abs() : BigDecimal.ZERO;

            Map<String, Object> posting = new LinkedHashMap<>();
            posting.put("TrxNo", 0);
            posting.put("TrxCode", chargeTrxCode);
            posting.put("TrxDate", businessDate);
            posting.put("TrxType", chargeTrxType);
            posting.put("UnitPrice", unitPrice);
            posting.put("Quantity", quantity);
            posting.put("Currency", invoice.getCurrency());
            posting.put("TaxInclusive", true);
            posting.put("ExchangeRate", BigDecimal.ONE);
            posting.put("TrxDateTime", businessDateTime);
            posting.put("LocalTrxDateTime", businessDateTime);
            posting.put("NetAmount", netAmount);
            posting.put("GrossAmount", grossAmount.abs());
            posting.put("GuestAccountDebit", debit);
            posting.put("GuestAccountCredit", credit);
            posting.put("ArrangementCode", null);
            posting.put("TranActionId", 0);
            posting.put("FinDmlSeqNo", 0);
            posting.put("Reference", String.valueOf(item.getId()));
            posting.put("Generates", Map.of("Generate", generates));
            postings.add(posting);

            Map<String, Object> trx = new LinkedHashMap<>();
            trx.put("HotelCode", hotelCode);
            trx.put("Group", chargeGroup);
            trx.put("SubGroup", chargeSubGroup);
            trx.put("Code", chargeTrxCode);
            trx.put("TrxType", chargeTrxType);
            trx.put("Description", chargeDescription);
            trx.put("TrxCodeType", chargeTrxCodeType);
            trx.put("Articles", null);
            trx.put("TranslatedDescriptions", null);
            trxInfo.add(trx);

            if (chargeMapping != null) {
                addBucket(bucketSummaryByKey, chargeMapping.getBucketCode(), chargeMapping.getBucketType(),
                        chargeMapping.getBucketValue(), chargeMapping.getBucketDescription(), chargeTrxCode, grossAmount.abs());
            }
        }

        for (InvoicePaymentAllocation allocation : allocations) {
            PaymentTransaction paymentTransaction = paymentTransactionService.requireById(allocation.getPaymentTransactionId());
            Optional<OperaFiscalPaymentMapping> paymentMappingOpt = operaFiscalMappingService.resolvePaymentMapping(
                    invoice.getTenantId(),
                    paymentTransaction.getPaymentType()
            );
            OperaFiscalPaymentMapping paymentMapping = paymentMappingOpt.orElse(null);

            String paymentTrxCode = paymentMapping != null && StringUtils.hasText(paymentMapping.getTrxCode())
                    ? paymentMapping.getTrxCode()
                    : paymentTrxCodeForType(paymentTransaction.getPaymentType(), request);
            String paymentTrxType = paymentMapping != null && StringUtils.hasText(paymentMapping.getTrxType())
                    ? paymentMapping.getTrxType()
                    : "FC";
            String paymentTrxCodeType = paymentMapping != null && StringUtils.hasText(paymentMapping.getTrxCodeType())
                    ? paymentMapping.getTrxCodeType()
                    : "O";
            String paymentGroup = firstNonBlank(
                    paymentMapping != null ? normalizeNullable(paymentMapping.getTrxGroup()) : null,
                    "PAY"
            );
            String paymentSubGroup = firstNonBlank(
                    paymentMapping != null ? normalizeNullable(paymentMapping.getTrxSubGroup()) : null,
                    paymentTransaction.getPaymentType()
            );
            String paymentDescription = firstNonBlank(
                    paymentMapping != null ? normalizeNullable(paymentMapping.getDescription()) : null,
                    paymentTransaction.getPaymentType()
            );

            BigDecimal amount = money(zeroSafe(allocation.getAllocatedAmount()));
            if (amount.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            BigDecimal debit = amount.signum() < 0 ? amount.abs() : BigDecimal.ZERO;
            BigDecimal credit = amount.signum() > 0 ? amount.abs() : BigDecimal.ZERO;

            Map<String, Object> paymentPosting = new LinkedHashMap<>();
            paymentPosting.put("TrxNo", 0);
            paymentPosting.put("TrxCode", paymentTrxCode);
            paymentPosting.put("TrxDate", businessDate);
            paymentPosting.put("TrxType", paymentTrxType);
            paymentPosting.put("UnitPrice", amount.abs());
            paymentPosting.put("Quantity", BigDecimal.ONE);
            paymentPosting.put("Currency", invoice.getCurrency());
            paymentPosting.put("TaxInclusive", false);
            paymentPosting.put("ExchangeRate", BigDecimal.ONE);
            paymentPosting.put("TrxDateTime", businessDateTime);
            paymentPosting.put("LocalTrxDateTime", businessDateTime);
            paymentPosting.put("NetAmount", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            paymentPosting.put("GrossAmount", amount.abs());
            paymentPosting.put("GuestAccountDebit", debit);
            paymentPosting.put("GuestAccountCredit", credit);
            paymentPosting.put("ArrangementCode", null);
            paymentPosting.put("TranActionId", 0);
            paymentPosting.put("FinDmlSeqNo", 0);
            paymentPosting.put("Reference", paymentTransaction.getId() != null ? paymentTransaction.getId().toString() : null);
            paymentPosting.put("Generates", Map.of("Generate", List.of()));
            postings.add(paymentPosting);

            Map<String, Object> trx = new LinkedHashMap<>();
            trx.put("HotelCode", hotelCode);
            trx.put("Group", paymentGroup);
            trx.put("SubGroup", paymentSubGroup);
            trx.put("Code", paymentTrxCode);
            trx.put("TrxType", paymentTrxType);
            trx.put("Description", paymentDescription);
            trx.put("TrxCodeType", paymentTrxCodeType);
            trx.put("Articles", null);
            trx.put("TranslatedDescriptions", null);
            trxInfo.add(trx);

            if (paymentMapping != null) {
                addBucket(bucketSummaryByKey, paymentMapping.getBucketCode(), paymentMapping.getBucketType(),
                        paymentMapping.getBucketValue(), paymentMapping.getBucketDescription(), paymentTrxCode, amount.abs());
            }
        }

        for (TaxSummary tax : taxSummaryByKey.values()) {
            Map<String, Object> taxEntry = new LinkedHashMap<>();
            taxEntry.put("Name", tax.taxName);
            taxEntry.put("Value", money(tax.taxValue.abs()));
            taxEntry.put("NetAmount", money(tax.netValue.abs()));
            taxEntry.put("Percent", tax.percent.setScale(2, RoundingMode.HALF_UP).toPlainString());
            taxEntry.put("Amount", "");
            taxes.add(taxEntry);
        }

        List<Map<String, Object>> revenueBuckets = buildRevenueBuckets(bucketSummaryByKey, billNo, grossAmountTotal, postings);

        AppUser currentUser = resolveAuthenticatedAppUser().orElse(null);
        String fiscalAppUser = firstNonBlank(normalizeNullable(request.getAppUser()), currentUser != null ? currentUser.getUsername() : "SYSTEM");
        String fiscalAppUserId = firstNonBlank(normalizeNullable(request.getAppUserId()), currentUser != null && currentUser.getId() != null ? currentUser.getId().toString() : "0");
        String employeeNumber = firstNonBlank(
                normalizeNullable(request.getEmployeeNumber()),
                currentUser != null ? normalizeNullable(currentUser.getEmployeeNumber()) : null,
                null
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("DepositsInfo", null);
        payload.put("DocumentInfo", buildDocumentInfo(lastSupportingDocumentInfo, hotelCode, billNo, businessDate, businessDateTime,
                application, propertyTaxNumber, countryCode, countryName, command, documentType, fiscalTimeout, terminalId, operaFiscalBillNo));
        payload.put("AdditionalInfo", request.getAdditionalInfo() != null ? request.getAdditionalInfo() : null);
        payload.put("UserDefinedFields", request.getUserDefinedFields() != null
                ? request.getUserDefinedFields()
                : buildMappedUserDefinedFields(invoice.getTenantId()));
        Map<String, Object> fiscalTerminalInfo = new LinkedHashMap<>();
        fiscalTerminalInfo.put("TerminalAddessAndPort", terminalAddressAndPort);
        fiscalTerminalInfo.put("TerminalID", terminalCode);
        payload.put("FiscalTerminalInfo", fiscalTerminalInfo);
        payload.put("FolioInfo", buildFolioInfo(invoice, billNo, businessDateTime, window, cashierNumber, fiscalFolioStatus, creditBill,
                postings, revenueBuckets, taxes, trxInfo, netAmountTotal, grossAmountTotal));

        Map<String, Object> hotelInfo = new LinkedHashMap<>();
        hotelInfo.put("HotelCode", hotelCode);
        hotelInfo.put("HotelName", firstNonBlank(
                fiscalConfig != null ? normalizeNullable(fiscalConfig.getHotelName()) : null,
                hotelCode
        ));
        hotelInfo.put("LegalOwner", firstNonBlank(
                fiscalConfig != null ? normalizeNullable(fiscalConfig.getLegalOwner()) : null,
                null
        ));
        hotelInfo.put("LocalCurrency", null);
        hotelInfo.put("Decimals", "2");
        hotelInfo.put("TimeZoneRegion", null);
        hotelInfo.put("PhoneNo", null);
        hotelInfo.put("Email", null);
        hotelInfo.put("WebPage", null);
        hotelInfo.put("PropertyDateTime", businessDateTime);
        hotelInfo.put("BusinessPremiseId1", businessPremiseCode);
        hotelInfo.put("BusinessPremiseId2", businessPremiseCode);
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("Address", null);
        address.put("Address1", null);
        address.put("AddresseeCountryDesc", null);
        address.put("City", null);
        address.put("Country", countryCode);
        address.put("IsoCode", null);
        address.put("PostalCode", null);
        address.put("Primary", false);
        address.put("Type", null);
        hotelInfo.put("Address", address);
        hotelInfo.put("ExchangeRates", null);
        payload.put("HotelInfo", request.getHotelInfo() != null ? request.getHotelInfo() : objectMapper.valueToTree(hotelInfo));

        payload.put("ReservationInfo", buildReservationInfo(invoice, reservationRequest, request));

        Map<String, Object> fiscalFolioUserInfo = new LinkedHashMap<>();
        fiscalFolioUserInfo.put("AppUser", fiscalAppUser);
        fiscalFolioUserInfo.put("AppUserId", fiscalAppUserId);
        fiscalFolioUserInfo.put("EmployeeNumber", employeeNumber);
        fiscalFolioUserInfo.put("CashierId", cashierId);
        payload.put("FiscalFolioUserInfo", request.getFiscalFolioUserInfo() != null
                ? request.getFiscalFolioUserInfo()
                : objectMapper.valueToTree(fiscalFolioUserInfo));
        payload.put("CollectingAgentPropertyInfo", request.getCollectingAgentPropertyInfo() != null ? request.getCollectingAgentPropertyInfo() : null);
        payload.put("VersionInfo", request.getVersionInfo() != null ? request.getVersionInfo() : null);
        payload.put("FiscalPartnerResponse", request.getFiscalPartnerResponse() != null ? request.getFiscalPartnerResponse() : null);
        return objectMapper.valueToTree(payload);
    }

    private List<Map<String, Object>> buildRevenueBuckets(Map<String, BucketSummary> bucketSummaryByKey,
                                                          String billNo,
                                                          BigDecimal grossAmountTotal,
                                                          List<Map<String, Object>> postings) {
        if (bucketSummaryByKey.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("BucketCode", "INVOICE_TOTAL");
            fallback.put("BucketType", "BOOKING_API");
            fallback.put("BucketValue", billNo);
            fallback.put("Description", "Invoice " + billNo);
            fallback.put("BucketCodeTotalGross", money(grossAmountTotal));
            fallback.put("TrxCode", postings.stream().map(p -> String.valueOf(p.get("TrxCode"))).distinct().toList());
            return List.of(fallback);
        }

        List<Map<String, Object>> buckets = new ArrayList<>();
        for (BucketSummary summary : bucketSummaryByKey.values()) {
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("BucketCode", summary.bucketCode);
            bucket.put("BucketType", summary.bucketType);
            bucket.put("BucketValue", summary.bucketValue);
            bucket.put("Description", summary.bucketDescription);
            bucket.put("BucketCodeTotalGross", money(summary.totalGross));
            bucket.put("TrxCode", new ArrayList<>(summary.trxCodes));
            buckets.add(bucket);
        }
        return buckets;
    }

    private void addBucket(Map<String, BucketSummary> bucketSummaryByKey,
                           String bucketCode,
                           String bucketType,
                           String bucketValue,
                           String bucketDescription,
                           String trxCode,
                           BigDecimal grossAmount) {
        if (!StringUtils.hasText(bucketCode)) {
            return;
        }
        String key = String.join("|",
                normalizeNullable(bucketCode),
                firstNonBlank(normalizeNullable(bucketType), ""),
                firstNonBlank(normalizeNullable(bucketValue), ""),
                firstNonBlank(normalizeNullable(bucketDescription), "")
        );
        BucketSummary summary = bucketSummaryByKey.computeIfAbsent(
                key,
                k -> new BucketSummary(
                        normalizeNullable(bucketCode),
                        normalizeNullable(bucketType),
                        normalizeNullable(bucketValue),
                        normalizeNullable(bucketDescription),
                        BigDecimal.ZERO,
                        new LinkedHashSet<>()
                )
        );
        summary.totalGross = summary.totalGross.add(zeroSafe(grossAmount));
        if (StringUtils.hasText(trxCode)) {
            summary.trxCodes.add(trxCode);
        }
    }

    private JsonNode buildMappedUserDefinedFields(Long tenantId) {
        List<OperaFiscalUdfMapping> mappings = operaFiscalMappingService.activeUdfMappings(tenantId);
        if (mappings.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> udfEntries = new ArrayList<>();
        for (OperaFiscalUdfMapping mapping : mappings) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("Name", mapping.getUdfName());
            entry.put("Value", mapping.getUdfValue());
            udfEntries.add(entry);
        }
        Map<String, Object> character = new LinkedHashMap<>();
        character.put("UDF", udfEntries);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("CharacterUDFs", List.of(character));
        result.put("NumericUDFs", null);
        result.put("DateUDFs", null);
        return objectMapper.valueToTree(result);
    }

    private Map<String, Object> buildDocumentInfo(Map<String, Object> lastSupportingDocumentInfo,
                                                  String hotelCode,
                                                  String billNo,
                                                  String businessDate,
                                                  String businessDateTime,
                                                  String application,
                                                  String propertyTaxNumber,
                                                  String countryCode,
                                                  String countryName,
                                                  String command,
                                                  String documentType,
                                                  String fiscalTimeout,
                                                  String terminalId,
                                                  String operaFiscalBillNo) {
        Map<String, Object> documentInfo = new LinkedHashMap<>();
        documentInfo.put("LastSupportingDocumentInfo", lastSupportingDocumentInfo);
        documentInfo.put("SupportingDocumentSeqNo", null);
        documentInfo.put("HotelCode", hotelCode);
        documentInfo.put("BillNo", billNo);
        documentInfo.put("FolioType", DEFAULT_OFIS_FOLIO_TYPE);
        documentInfo.put("TerminalId", terminalId);
        documentInfo.put("ProgramName", "0");
        documentInfo.put("FiscalFolioId", "0");
        documentInfo.put("OperaFiscalBillNo", operaFiscalBillNo);
        documentInfo.put("Application", application);
        documentInfo.put("PropertyTaxNumber", propertyTaxNumber);
        documentInfo.put("BankName", "");
        documentInfo.put("BankCode", "");
        documentInfo.put("BankIdType", "");
        documentInfo.put("BankIdCode", "");
        documentInfo.put("BusinessDate", businessDate);
        documentInfo.put("BusinessDateTime", businessDateTime);
        documentInfo.put("CountryCode", countryCode);
        documentInfo.put("CountryName", countryName);
        documentInfo.put("Command", command);
        documentInfo.put("DocumentType", documentType);
        documentInfo.put("FiscalTimeoutPeriod", fiscalTimeout);
        return documentInfo;
    }

    private Map<String, Object> buildFolioInfo(Invoice invoice,
                                               String billNo,
                                               String businessDateTime,
                                               String window,
                                               String cashierNumber,
                                               String fiscalFolioStatus,
                                               boolean creditBill,
                                               List<Map<String, Object>> postings,
                                               List<Map<String, Object>> revenueBuckets,
                                               List<Map<String, Object>> taxes,
                                               List<Map<String, Object>> trxInfo,
                                               BigDecimal netAmountTotal,
                                               BigDecimal grossAmountTotal) {
        Map<String, Object> folioHeader = new LinkedHashMap<>();
        folioHeader.put("AssociatedFiscalTerminalInfo", null);
        folioHeader.put("BillGenerationDate", businessDateTime);
        folioHeader.put("FolioType", DEFAULT_OFIS_FOLIO_TYPE);
        folioHeader.put("CreditBill", creditBill);
        folioHeader.put("FolioNo", billNo);
        folioHeader.put("BillNo", billNo);
        folioHeader.put("InvoiceCurrencyCode", invoice.getCurrency());
        folioHeader.put("InvoiceCurrencyRate", "1");
        folioHeader.put("Window", window);
        folioHeader.put("CashierNumber", cashierNumber);
        folioHeader.put("FiscalFolioStatus", fiscalFolioStatus);
        folioHeader.put("LocalBillGenerationDate", businessDateTime);
        folioHeader.put("FolioTypeUniqueCode", "0");
        folioHeader.put("CollectingAgentTaxes", null);
        folioHeader.put("AssociatedFolioInfo", null);

        Map<String, Object> totalInfo = new LinkedHashMap<>();
        totalInfo.put("NetAmount", money(netAmountTotal));
        totalInfo.put("GrossAmount", money(grossAmountTotal));
        totalInfo.put("NonTaxableAmount", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        totalInfo.put("PaidOut", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        totalInfo.put("Taxes", Map.of("Tax", taxes));

        Map<String, Object> folioInfo = new LinkedHashMap<>();
        folioInfo.put("ArrangementInfo", null);
        folioInfo.put("FolioHeaderInfo", folioHeader);
        folioInfo.put("PayeeInfo", null);
        folioInfo.put("Postings", postings);
        folioInfo.put("RevenueBucketInfo", revenueBuckets);
        folioInfo.put("TotalInfo", totalInfo);
        folioInfo.put("TrxInfo", trxInfo);
        folioInfo.put("PosChequeInfo", null);
        return folioInfo;
    }

    private Map<String, Object> buildLastSupportingDocumentInfo(Invoice invoice) {
        String documentNo1 = "";
        String documentNo2 = "";
        String specialId = "";
        if (REFERENCE_TABLE_INVOICE.equalsIgnoreCase(invoice.getReferenceTable()) && invoice.getReferenceId() != null) {
            Optional<Invoice> sourceOpt = invoiceRepo.findById(invoice.getReferenceId());
            if (sourceOpt.isPresent()) {
                Invoice source = sourceOpt.get();
                documentNo1 = firstNonBlank(source.getFiscalDocumentNo1(), "");
                documentNo2 = firstNonBlank(source.getFiscalDocumentNo2(), "");
                specialId = firstNonBlank(source.getFiscalSpecialId(), "");
            }
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("DocumentNo1", documentNo1);
        value.put("DocumentNo2", documentNo2);
        value.put("SpecialId", specialId);
        return value;
    }

    private JsonNode buildReservationInfo(Invoice invoice,
                                          ReservationRequest reservationRequest,
                                          InvoiceFiscalizeRequest request) {
        Map<String, Object> reservationInfo = new LinkedHashMap<>();
        reservationInfo.put("ConfirmationNo", null);
        reservationInfo.put("ResvNameID", null);
        reservationInfo.put("ArrivalDate", null);
        reservationInfo.put("ArrivalTime", null);
        reservationInfo.put("NumberOfNights", null);
        reservationInfo.put("DepartureDate", null);
        reservationInfo.put("NumAdults", null);
        reservationInfo.put("NumChilds", null);
        reservationInfo.put("GuestInfo", null);
        reservationInfo.put("ChildAgeBucket1", null);
        reservationInfo.put("ChildAgeBucket2", null);
        reservationInfo.put("ChildAgeBucket3", null);
        reservationInfo.put("RoomRate", 0.0);
        reservationInfo.put("RatePlanCode", null);
        reservationInfo.put("RoomNumber", null);
        reservationInfo.put("RoomClass", null);
        reservationInfo.put("RoomType", null);
        reservationInfo.put("NumberOfRooms", null);
        reservationInfo.put("Guarantee", null);
        reservationInfo.put("MarketCode", null);
        reservationInfo.put("ResStatus", null);
        reservationInfo.put("UserDefinedFields", null);
        reservationInfo.put("SourceCode", null);
        reservationInfo.put("SourceGroup", null);
        reservationInfo.put("ExternalRefInfo", null);
        reservationInfo.put("LinkedProfiles", null);
        reservationInfo.put("AccompanyingGuestInfo", null);

        ObjectNode reservationInfoNode = objectMapper.valueToTree(reservationInfo);
        JsonNode requestedReservationInfo = request.getReservationInfo();
        if (requestedReservationInfo != null && !requestedReservationInfo.isNull()) {
            if (!requestedReservationInfo.isObject()) {
                throw new IllegalArgumentException("reservationInfo must be a JSON object");
            }
            reservationInfoNode.setAll((ObjectNode) requestedReservationInfo);
        }

        String confirmationNo = firstNonBlank(
                normalizeNullable(request.getConfirmationNo()),
                firstNonBlank(
                        nodeText(reservationInfoNode.get("ConfirmationNo")),
                        reservationRequest != null ? normalizeNullable(reservationRequest.getConfirmationCode()) : null
                ),
                invoice.getId() != null ? invoice.getId().toString() : null
        );
        if (confirmationNo == null) {
            reservationInfoNode.putNull("ConfirmationNo");
        } else {
            reservationInfoNode.put("ConfirmationNo", confirmationNo);
        }

        String resvNameId = firstNonBlank(
                normalizeNullable(request.getReservationOperaId()),
                nodeText(reservationInfoNode.get("ResvNameID"))
        );
        if (resvNameId == null) {
            reservationInfoNode.putNull("ResvNameID");
        } else {
            reservationInfoNode.put("ResvNameID", resvNameId);
        }

        return reservationInfoNode;
    }

    private void addTaxGenerate(List<Map<String, Object>> generates,
                                Map<String, TaxSummary> taxSummaryByKey,
                                Long tenantId,
                                String requestTrxCodeCandidate,
                                String defaultTrxCode,
                                BigDecimal taxPercent,
                                BigDecimal taxAmount,
                                BigDecimal quantity,
                                String businessDate,
                                String businessDateTime,
                                String currency) {
        BigDecimal normalizedTaxAmount = money(zeroSafe(taxAmount));
        BigDecimal normalizedTaxPercent = zeroSafe(taxPercent).setScale(4, RoundingMode.HALF_UP);
        if (normalizedTaxAmount.compareTo(BigDecimal.ZERO) == 0 || normalizedTaxPercent.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        Optional<OperaFiscalTaxMapping> taxMappingOpt = operaFiscalMappingService.resolveTaxMapping(tenantId, normalizedTaxPercent);
        OperaFiscalTaxMapping taxMapping = taxMappingOpt.orElse(null);

        String trxCode = taxMapping != null && StringUtils.hasText(taxMapping.getGenerateTrxCode())
                ? taxMapping.getGenerateTrxCode()
                : firstNonBlank(normalizeNullable(requestTrxCodeCandidate), defaultTrxCode);
        String taxName = firstNonBlank(taxMapping != null ? normalizeNullable(taxMapping.getTaxName()) : null, trxCode);

        BigDecimal quantityDivisor = quantity.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE : quantity;
        BigDecimal unitPrice = normalizedTaxAmount.divide(quantityDivisor, 6, RoundingMode.HALF_UP);
        BigDecimal netAmount = normalizedTaxAmount.multiply(HUNDRED).divide(normalizedTaxPercent, 6, RoundingMode.HALF_UP);

        Map<String, Object> generate = new LinkedHashMap<>();
        generate.put("Currency", currency);
        generate.put("ExchangeRate", BigDecimal.ONE);
        generate.put("LocalTrxDateTime", businessDateTime);
        generate.put("Quantity", quantity);
        generate.put("TaxInclusive", true);
        generate.put("TaxRate", normalizedTaxPercent.setScale(2, RoundingMode.HALF_UP));
        generate.put("TrxCode", trxCode);
        generate.put("TrxDate", businessDate);
        generate.put("TrxDateTime", businessDateTime);
        generate.put("TrxNo", 0);
        generate.put("TrxType", "C");
        generate.put("UnitPrice", unitPrice);
        generate.put("FinDmlSeqNo", 0);
        generate.put("NetAmount", netAmount);
        generate.put("Reference", null);
        generate.put("TranActionId", 0);
        generate.put("TrxNoAddedBy", 0);
        generates.add(generate);

        String key = taxName + "|" + normalizedTaxPercent.setScale(2, RoundingMode.HALF_UP);
        TaxSummary summary = taxSummaryByKey.computeIfAbsent(
                key,
                k -> new TaxSummary(taxName, normalizedTaxPercent.setScale(2, RoundingMode.HALF_UP), BigDecimal.ZERO, BigDecimal.ZERO)
        );
        summary.taxValue = summary.taxValue.add(normalizedTaxAmount);
        summary.netValue = summary.netValue.add(netAmount);
    }

    private void applyFiscalResponse(Invoice invoice, JsonNode response) {
        invoice.setFiscalLastResponsePayload(response);
        invoice.setFiscalFolioNo(normalizeNullable(response.path("FiscalFolioNo").asText(null)));
        invoice.setFiscalizedAt(resolveFiscalizedAt(response, invoice.getIssuedAt()));
        invoice.setFiscalDocumentNo1(extractFiscalOutputValue(response, "DOCUMENT_NO_1"));
        invoice.setFiscalDocumentNo2(extractFiscalOutputValue(response, "DOCUMENT_NO_2"));
        invoice.setFiscalSpecialId(firstNonBlank(
                extractFiscalOutputValue(response, "SPECIAL_ID"),
                normalizeNullable(response.path("FiscalFolioNo").asText(null))
        ));
        invoice.setFiscalQrUrl(extractFiscalOutputValue(response, "BASE64_TO_IMAGE"));
        invoice.setFiscalErrorMessage(null);
    }

    private OffsetDateTime resolveFiscalizedAt(JsonNode response, OffsetDateTime fallback) {
        String value = normalizeNullable(response.path("FiscalBillGenerationDateTime").asText(null));
        if (value == null) {
            return fallback != null ? fallback : OffsetDateTime.now();
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception ignored) {
            return fallback != null ? fallback : OffsetDateTime.now();
        }
    }

    private String extractFiscalOutputValue(JsonNode response, String outputName) {
        JsonNode outputs = response.path("FiscalOutputs").path("Output");
        if (!outputs.isArray()) {
            return null;
        }
        for (JsonNode output : outputs) {
            String name = normalizeNullable(output.path("Name").asText(null));
            if (outputName.equalsIgnoreCase(name)) {
                return normalizeNullable(output.path("Value").asText(null));
            }
        }
        return null;
    }

    private String paymentTrxCodeForType(String paymentType, InvoiceFiscalizeRequest request) {
        String normalizedType = paymentType == null ? "" : paymentType.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedType) {
            case "CASH" -> firstNonBlank(normalizeNullable(request.getCashPaymentTrxCode()), DEFAULT_OFIS_CASH_PAYMENT_TRX_CODE);
            case "CARD" -> firstNonBlank(normalizeNullable(request.getCardPaymentTrxCode()), DEFAULT_OFIS_CARD_PAYMENT_TRX_CODE);
            case "BANK_TRANSFER" -> firstNonBlank(normalizeNullable(request.getBankPaymentTrxCode()), DEFAULT_OFIS_BANK_PAYMENT_TRX_CODE);
            case "ROOM_CHARGE" -> firstNonBlank(normalizeNullable(request.getRoomChargePaymentTrxCode()), DEFAULT_OFIS_ROOM_CHARGE_PAYMENT_TRX_CODE);
            default -> firstNonBlank(normalizeNullable(request.getCardPaymentTrxCode()), DEFAULT_OFIS_CARD_PAYMENT_TRX_CODE);
        };
    }

    private BigDecimal resolveTaxAmount(BigDecimal explicitTaxAmount,
                                        BigDecimal taxPercent,
                                        BigDecimal netAmount,
                                        BigDecimal grossAmount) {
        BigDecimal explicit = money(zeroSafe(explicitTaxAmount));
        if (explicit.compareTo(BigDecimal.ZERO) != 0) {
            return explicit;
        }

        BigDecimal percent = zeroSafe(taxPercent).setScale(4, RoundingMode.HALF_UP);
        if (percent.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal normalizedNet = money(zeroSafe(netAmount));
        if (normalizedNet.compareTo(BigDecimal.ZERO) != 0) {
            return money(normalizedNet.multiply(percent).divide(HUNDRED, 8, RoundingMode.HALF_UP));
        }

        BigDecimal normalizedGross = money(zeroSafe(grossAmount));
        if (normalizedGross.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal divisor = BigDecimal.ONE.add(percent.divide(HUNDRED, 8, RoundingMode.HALF_UP));
        BigDecimal inferredNet = normalizedGross.divide(divisor, 8, RoundingMode.HALF_UP);
        return money(normalizedGross.subtract(inferredNet));
    }

    private String requireRequestValue(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }

    private Optional<AppUser> resolveAuthenticatedAppUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof String username) || !StringUtils.hasText(username)) {
            return Optional.empty();
        }
        if (username.startsWith("api-token:")) {
            return Optional.empty();
        }
        return appUserRepo.findByUsername(username);
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String nodeText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return normalizeNullable(node.asText(null));
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private String firstNonBlank(String primary, String secondary, String fallback) {
        return firstNonBlank(firstNonBlank(primary, secondary), fallback);
    }

    private IssuedByMode firstNonNull(IssuedByMode primary, IssuedByMode fallback) {
        return primary != null ? primary : fallback;
    }

    private BigDecimal zeroSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal money(BigDecimal value) {
        return zeroSafe(value).setScale(2, RoundingMode.HALF_UP);
    }

    private boolean isStornoType(InvoiceType invoiceType) {
        return invoiceType != null && invoiceType.isStornoType();
    }

    private static class TaxSummary {
        private final String taxName;
        private final BigDecimal percent;
        private BigDecimal taxValue;
        private BigDecimal netValue;

        private TaxSummary(String taxName, BigDecimal percent, BigDecimal taxValue, BigDecimal netValue) {
            this.taxName = taxName;
            this.percent = percent;
            this.taxValue = taxValue;
            this.netValue = netValue;
        }
    }

    private static class BucketSummary {
        private final String bucketCode;
        private final String bucketType;
        private final String bucketValue;
        private final String bucketDescription;
        private BigDecimal totalGross;
        private final LinkedHashSet<String> trxCodes;

        private BucketSummary(String bucketCode,
                              String bucketType,
                              String bucketValue,
                              String bucketDescription,
                              BigDecimal totalGross,
                              LinkedHashSet<String> trxCodes) {
            this.bucketCode = bucketCode;
            this.bucketType = bucketType;
            this.bucketValue = bucketValue;
            this.bucketDescription = bucketDescription;
            this.totalGross = totalGross;
            this.trxCodes = trxCodes;
        }
    }
}
