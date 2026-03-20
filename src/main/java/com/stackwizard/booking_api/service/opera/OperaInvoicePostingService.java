package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.dto.OperaInvoicePostRequest;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceItem;
import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import com.stackwizard.booking_api.model.InvoiceStatus;
import com.stackwizard.booking_api.model.OperaFiscalChargeMapping;
import com.stackwizard.booking_api.model.OperaFiscalPaymentMapping;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.model.OperaInvoiceTypeRouting;
import com.stackwizard.booking_api.model.OperaPostingStatus;
import com.stackwizard.booking_api.model.OperaPostingTarget;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.repository.InvoiceItemRepository;
import com.stackwizard.booking_api.repository.InvoicePaymentAllocationRepository;
import com.stackwizard.booking_api.repository.InvoiceRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.service.PaymentTransactionService;
import com.stackwizard.booking_api.service.fiscal.OperaFiscalMappingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class OperaInvoicePostingService {
    private final InvoiceRepository invoiceRepo;
    private final InvoiceItemRepository invoiceItemRepo;
    private final InvoicePaymentAllocationRepository allocationRepo;
    private final ProductRepository productRepo;
    private final PaymentTransactionService paymentTransactionService;
    private final OperaFiscalMappingService operaFiscalMappingService;
    private final OperaPostingConfigurationService configurationService;
    private final OperaTenantConfigResolver tenantConfigResolver;
    private final OperaPostingClient operaPostingClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OperaInvoicePostingService(InvoiceRepository invoiceRepo,
                                      InvoiceItemRepository invoiceItemRepo,
                                      InvoicePaymentAllocationRepository allocationRepo,
                                      ProductRepository productRepo,
                                      PaymentTransactionService paymentTransactionService,
                                      OperaFiscalMappingService operaFiscalMappingService,
                                      OperaPostingConfigurationService configurationService,
                                      OperaTenantConfigResolver tenantConfigResolver,
                                      OperaPostingClient operaPostingClient) {
        this.invoiceRepo = invoiceRepo;
        this.invoiceItemRepo = invoiceItemRepo;
        this.allocationRepo = allocationRepo;
        this.productRepo = productRepo;
        this.paymentTransactionService = paymentTransactionService;
        this.operaFiscalMappingService = operaFiscalMappingService;
        this.configurationService = configurationService;
        this.tenantConfigResolver = tenantConfigResolver;
        this.operaPostingClient = operaPostingClient;
    }

    @Transactional(readOnly = true)
    public OperaInvoicePostingPreview previewInvoice(Long invoiceId, OperaInvoicePostRequest request) {
        PreparedPosting prepared = preparePosting(invoiceId, request, false);
        return new OperaInvoicePostingPreview(
                prepared.invoice(),
                prepared.target().postingTarget(),
                prepared.target().hotel().getHotelCode(),
                prepared.target().reservationId(),
                prepared.target().cashierId(),
                prepared.target().folioWindowNo(),
                prepared.payload()
        );
    }

    @Transactional(readOnly = true)
    public JsonNode previewPayload(Long invoiceId, OperaInvoicePostRequest request) {
        return preparePosting(invoiceId, request, false).payload();
    }

    @Transactional(noRollbackFor = Exception.class)
    public OperaInvoicePostingResult postInvoice(Long invoiceId, OperaInvoicePostRequest request) {
        PreparedPosting prepared = preparePosting(invoiceId, request, true);
        Invoice invoice = prepared.invoice();
        if (effectivePostingStatus(invoice) == OperaPostingStatus.POSTED
                && !Boolean.TRUE.equals(request != null ? request.getForce() : null)) {
            throw new IllegalStateException("Invoice is already posted to Opera; use force=true to repost");
        }

        OperaTenantConfigResolver.OperaResolvedConfig config = resolveConfig(invoice.getTenantId(), request);
        try {
            invoice.setOperaLastRequestPayload(prepared.payload());
            invoice.setOperaErrorMessage(null);
            invoiceRepo.save(invoice);

            JsonNode response = operaPostingClient.postChargesAndPayments(
                    config,
                    prepared.target().hotel().getHotelCode(),
                    prepared.target().reservationId(),
                    prepared.payload()
            );

            invoice.setOperaPostingStatus(OperaPostingStatus.POSTED);
            invoice.setOperaPostedAt(OffsetDateTime.now());
            invoice.setOperaReservationId(prepared.target().reservationId());
            invoice.setOperaHotelCode(prepared.target().hotel().getHotelCode());
            invoice.setOperaLastResponsePayload(response);
            invoice.setOperaErrorMessage(null);
            Invoice saved = invoiceRepo.save(invoice);

            return new OperaInvoicePostingResult(
                    saved,
                    prepared.target().postingTarget(),
                    prepared.target().hotel().getHotelCode(),
                    prepared.target().reservationId(),
                    prepared.target().cashierId(),
                    prepared.target().folioWindowNo(),
                    prepared.payload(),
                    response
            );
        } catch (Exception ex) {
            invoice.setOperaPostingStatus(OperaPostingStatus.FAILED);
            invoice.setOperaLastRequestPayload(prepared.payload());
            invoice.setOperaErrorMessage(ex.getMessage());
            invoiceRepo.save(invoice);
            throw ex;
        }
    }

    private PreparedPosting preparePosting(Long invoiceId, OperaInvoicePostRequest request, boolean requireIssuedInvoice) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        if (requireIssuedInvoice && invoice.getStatus() != InvoiceStatus.ISSUED) {
            throw new IllegalStateException("Only ISSUED invoices can be posted to Opera");
        }

        List<InvoiceItem> items = invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(invoiceId);
        if (items.isEmpty()) {
            throw new IllegalStateException("Invoice has no items to post");
        }
        List<InvoicePaymentAllocation> allocations = allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(invoiceId);
        ResolvedTarget target = resolveTarget(invoice, request);
        JsonNode payload = buildPayload(invoice, items, allocations, target, request);
        return new PreparedPosting(invoice, target, payload);
    }

    private ResolvedTarget resolveTarget(Invoice invoice, OperaInvoicePostRequest request) {
        String overrideHotelCode = normalizeHotelCode(request != null ? request.getHotelCode() : null);
        Long overrideReservationId = normalizePositiveLong(request != null ? request.getReservationId() : null);
        OperaPostingTarget postingTarget = invoice.resolveOperaPostingTarget();
        String invoiceHotelCode = normalizeHotelCode(invoice.getOperaHotelCode());
        Long invoiceReservationId = normalizePositiveLong(invoice.getOperaReservationId());
        String defaultHotelCode = postingTarget == OperaPostingTarget.RESERVATION
                ? null
                : tenantConfigResolver.findDefaultHotelCode(invoice.getTenantId()).orElse(null);

        String resolvedHotelCode;
        Long resolvedReservationId;

        if (postingTarget == OperaPostingTarget.RESERVATION) {
            if ((overrideHotelCode == null) != (overrideReservationId == null)) {
                throw new IllegalArgumentException("ROOM_CHARGE override requires both hotelCode and reservationId");
            }
            resolvedHotelCode = firstNonBlank(overrideHotelCode, invoiceHotelCode);
            resolvedReservationId = firstNonNull(overrideReservationId, invoiceReservationId);
            if (!StringUtils.hasText(resolvedHotelCode) || resolvedReservationId == null) {
                throw new IllegalArgumentException("ROOM_CHARGE invoices require operaHotelCode and operaReservationId");
            }
        } else if (overrideReservationId != null) {
            resolvedHotelCode = firstNonBlank(overrideHotelCode, invoiceHotelCode, defaultHotelCode);
            if (!StringUtils.hasText(resolvedHotelCode)) {
                throw new IllegalArgumentException("hotelCode is required when overriding reservationId");
            }
            resolvedReservationId = overrideReservationId;
        } else if (overrideHotelCode == null && invoiceHotelCode != null && invoiceReservationId != null) {
            resolvedHotelCode = invoiceHotelCode;
            resolvedReservationId = invoiceReservationId;
        } else {
            String preferredHotelCode = firstNonBlank(overrideHotelCode, invoiceHotelCode, defaultHotelCode);
            OperaInvoiceTypeRouting routing = configurationService.resolveRouting(
                    invoice.getTenantId(),
                    invoice.getInvoiceType(),
                    preferredHotelCode
            );
            resolvedHotelCode = routing.getHotelCode();
            resolvedReservationId = routing.getReservationId();
        }

        OperaHotel hotel = configurationService.requireActiveHotel(invoice.getTenantId(), resolvedHotelCode);
        Long cashierId = resolveCashierId(request, hotel);
        Integer folioWindowNo = resolveFolioWindowNo(request, hotel);
        return new ResolvedTarget(postingTarget, hotel, resolvedReservationId, cashierId, folioWindowNo);
    }

    private JsonNode buildPayload(Invoice invoice,
                                  List<InvoiceItem> items,
                                  List<InvoicePaymentAllocation> allocations,
                                  ResolvedTarget target,
                                  OperaInvoicePostRequest request) {
        Map<Long, Product> productsById = new HashMap<>();
        List<Long> productIds = items.stream()
                .map(InvoiceItem::getProductId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (!productIds.isEmpty()) {
            for (Product product : productRepo.findAllById(productIds)) {
                productsById.put(product.getId(), product);
            }
        }

        String defaultPostingValue = firstNonBlank(normalizeNullable(invoice.getFiscalFolioNo()), invoice.getInvoiceNumber());
        String postingReference = defaultPostingValue;
        String paymentPostingRemark = defaultPostingValue;
        String comments = firstNonBlank(normalizeNullable(request != null ? request.getComments() : null), paymentPostingRemark);
        String paymentAction = firstNonBlank(normalizeNullable(request != null ? request.getPaymentAction() : null), "Billing");
        boolean applyRoutingInstructions = Boolean.TRUE.equals(request != null ? request.getApplyRoutingInstructions() : null);
        boolean autoPosting = request == null || request.getAutoPosting() == null || Boolean.TRUE.equals(request.getAutoPosting());

        List<Map<String, Object>> charges = new ArrayList<>();
        for (InvoiceItem item : items) {
            Product product = item.getProductId() != null ? productsById.get(item.getProductId()) : null;
            OperaFiscalChargeMapping chargeMapping = operaFiscalMappingService.resolveChargeMapping(
                            invoice.getTenantId(),
                            item.getProductId(),
                            product != null ? product.getProductType() : null
                    )
                    .orElseThrow(() -> new IllegalStateException("Opera charge mapping is missing for invoice item " + item.getId()));

            int postingQuantity = item.getQuantity() != null && item.getQuantity() != 0 ? Math.abs(item.getQuantity()) : 1;
            BigDecimal quantity = BigDecimal.valueOf(postingQuantity);
            BigDecimal grossAmount = money(zeroSafe(item.getGrossAmount()));
            BigDecimal unitAmount = postingQuantity == 0
                    ? grossAmount
                    : money(grossAmount.divide(quantity, 2, RoundingMode.HALF_UP));

            Map<String, Object> charge = new LinkedHashMap<>();
            String chargePostingRemark = firstNonBlank(normalizeNullable(item.getProductName()), paymentPostingRemark);
            charge.put("transactionCode", chargeMapping.getTrxCode());
            charge.put("price", amountPayload(unitAmount, invoice.getCurrency()));
            charge.put("postingQuantity", postingQuantity);
            charge.put("postingReference", postingReference);
            charge.put("postingRemark", chargePostingRemark);
            charge.put("applyRoutingInstructions", applyRoutingInstructions);
            charge.put("autoPosting", autoPosting);
            charge.put("folioWindowNo", target.folioWindowNo());
            charge.put("cashierId", target.cashierId());
            charges.add(charge);
        }

        List<Map<String, Object>> payments = new ArrayList<>();
        for (InvoicePaymentAllocation allocation : allocations) {
            BigDecimal postingAmount = money(zeroSafe(allocation.getAllocatedAmount()));
            if (postingAmount.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            PaymentTransaction paymentTransaction = paymentTransactionService.requireById(allocation.getPaymentTransactionId());
            OperaFiscalPaymentMapping paymentMapping = operaFiscalMappingService.resolvePaymentMapping(
                            invoice.getTenantId(),
                            paymentTransaction.getPaymentType(),
                            paymentTransaction.getCardType()
                    )
                    .orElseThrow(() -> new IllegalStateException(
                            "Opera payment mapping is missing for payment type " + paymentTransaction.getPaymentType()
                    ));

            String paymentMethodCode = normalizeNullable(paymentMapping.getPaymentMethodCode());
            if (!StringUtils.hasText(paymentMethodCode)) {
                throw new IllegalStateException("Opera payment method code is missing for payment mapping " + paymentMapping.getId());
            }

            Map<String, Object> paymentMethod = new LinkedHashMap<>();
            paymentMethod.put("paymentMethod", paymentMethodCode);
            paymentMethod.put("folioView", target.folioWindowNo());

            Map<String, Object> payment = new LinkedHashMap<>();
            payment.put("hotelId", target.hotel().getHotelCode());
            payment.put("paymentMethod", paymentMethod);
            payment.put("postingAmount", amountPayload(postingAmount, invoice.getCurrency()));
            payment.put("postingReference", postingReference);
            payment.put("postingRemark", paymentPostingRemark);
            payment.put("comments", comments);
            payment.put("action", paymentAction);
            payment.put("folioWindowNo", target.folioWindowNo());
            payment.put("cashierId", target.cashierId());
            payments.add(payment);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("charges", charges);
        payload.put("payments", payments);
        payload.put("cashierId", target.cashierId());
        return objectMapper.valueToTree(payload);
    }

    private Map<String, Object> amountPayload(BigDecimal amount, String currencyCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", money(zeroSafe(amount)));
        payload.put("currencyCode", currencyCode);
        return payload;
    }

    private OperaTenantConfigResolver.OperaResolvedConfig resolveConfig(Long tenantId, OperaInvoicePostRequest request) {
        OperaTenantConfigResolver.OperaResolvedConfig configured = null;
        try {
            configured = tenantConfigResolver.resolve(tenantId);
        } catch (IllegalStateException ex) {
            if (!hasRequestConfigOverrides(request)) {
                throw ex;
            }
        }

        String baseUrl = firstNonBlank(normalizeNullable(request != null ? request.getBaseUrl() : null),
                configured != null ? configured.baseUrl() : null);
        String appKey = firstNonBlank(normalizeNullable(request != null ? request.getAppKey() : null),
                configured != null ? configured.appKey() : null);
        String requestAccessToken = normalizeNullable(request != null ? request.getAccessToken() : null);
        String oauthPath = configured != null ? configured.oauthPath() : null;
        String clientId = configured != null ? configured.clientId() : null;
        String clientSecret = configured != null ? configured.clientSecret() : null;
        String enterpriseId = configured != null ? configured.enterpriseId() : null;
        String accessToken = requestAccessToken;

        requireConfigValue(baseUrl, "baseUrl");
        requireConfigValue(appKey, "appKey");
        if (!StringUtils.hasText(accessToken)) {
            requireConfigValue(oauthPath, "oauthPath");
            requireConfigValue(clientId, "clientId");
            requireConfigValue(clientSecret, "clientSecret");
            requireConfigValue(enterpriseId, "enterpriseId");
        }
        return new OperaTenantConfigResolver.OperaResolvedConfig(
                baseUrl,
                oauthPath,
                appKey,
                clientId,
                clientSecret,
                enterpriseId,
                accessToken
        );
    }

    private boolean hasRequestConfigOverrides(OperaInvoicePostRequest request) {
        return request != null
                && (StringUtils.hasText(request.getBaseUrl())
                || StringUtils.hasText(request.getAppKey())
                || StringUtils.hasText(request.getAccessToken()));
    }

    private void requireConfigValue(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Opera " + fieldName + " is required");
        }
    }

    private OperaPostingStatus effectivePostingStatus(Invoice invoice) {
        return invoice.getOperaPostingStatus() != null ? invoice.getOperaPostingStatus() : OperaPostingStatus.NOT_POSTED;
    }

    private Long resolveCashierId(OperaInvoicePostRequest request, OperaHotel hotel) {
        Long cashierId = request != null && request.getCashierId() != null ? request.getCashierId() : hotel.getDefaultCashierId();
        if (cashierId == null || cashierId <= 0) {
            throw new IllegalArgumentException("cashierId is required; configure it on the hotel or provide it in the request");
        }
        return cashierId;
    }

    private Integer resolveFolioWindowNo(OperaInvoicePostRequest request, OperaHotel hotel) {
        Integer folioWindowNo = request != null && request.getFolioWindowNo() != null
                ? request.getFolioWindowNo()
                : hotel.getDefaultFolioWindowNo();
        if (folioWindowNo == null) {
            return 1;
        }
        if (folioWindowNo <= 0) {
            throw new IllegalArgumentException("folioWindowNo must be greater than zero");
        }
        return folioWindowNo;
    }

    private String normalizeHotelCode(String hotelCode) {
        if (!StringUtils.hasText(hotelCode)) {
            return null;
        }
        return hotelCode.trim().toUpperCase(Locale.ROOT);
    }

    private Long normalizePositiveLong(Long value) {
        return value != null && value > 0 ? value : null;
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (StringUtils.hasText(primary)) {
            return primary;
        }
        return fallback;
    }

    private String firstNonBlank(String primary, String secondary, String fallback) {
        return firstNonBlank(firstNonBlank(primary, secondary), fallback);
    }

    private <T> T firstNonNull(T primary, T fallback) {
        return primary != null ? primary : fallback;
    }

    private BigDecimal zeroSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal money(BigDecimal value) {
        return zeroSafe(value).setScale(2, RoundingMode.HALF_UP);
    }

    private record PreparedPosting(Invoice invoice, ResolvedTarget target, JsonNode payload) {
    }

    private record ResolvedTarget(OperaPostingTarget postingTarget,
                                  OperaHotel hotel,
                                  Long reservationId,
                                  Long cashierId,
                                  Integer folioWindowNo) {
    }
}
