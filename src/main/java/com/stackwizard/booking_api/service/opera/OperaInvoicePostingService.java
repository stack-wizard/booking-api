package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.dto.OperaInvoicePostRequest;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceItem;
import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import com.stackwizard.booking_api.model.InvoiceStatus;
import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.model.OperaFiscalChargeMapping;
import com.stackwizard.booking_api.model.OperaFiscalPaymentMapping;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.model.OperaInvoiceTypeRouting;
import com.stackwizard.booking_api.model.OperaPostingStatus;
import com.stackwizard.booking_api.model.OperaPostingTarget;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.repository.InvoiceItemRepository;
import com.stackwizard.booking_api.repository.InvoicePaymentAllocationRepository;
import com.stackwizard.booking_api.repository.InvoiceRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.service.PaymentTransactionService;
import com.stackwizard.booking_api.service.fiscal.OperaFiscalMappingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OperaInvoicePostingService {
    private static final Logger log = LoggerFactory.getLogger(OperaInvoicePostingService.class);

    private final InvoiceRepository invoiceRepo;
    private final InvoiceItemRepository invoiceItemRepo;
    private final InvoicePaymentAllocationRepository allocationRepo;
    private final ProductRepository productRepo;
    private final PaymentTransactionService paymentTransactionService;
    private final OperaFiscalMappingService operaFiscalMappingService;
    private final OperaPostingConfigurationService configurationService;
    private final OperaTenantConfigResolver tenantConfigResolver;
    private final OperaPostingClient operaPostingClient;
    private final ReservationRepository reservationRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OperaInvoicePostingService(InvoiceRepository invoiceRepo,
                                      InvoiceItemRepository invoiceItemRepo,
                                      InvoicePaymentAllocationRepository allocationRepo,
                                      ProductRepository productRepo,
                                      PaymentTransactionService paymentTransactionService,
                                      OperaFiscalMappingService operaFiscalMappingService,
                                      OperaPostingConfigurationService configurationService,
                                      OperaTenantConfigResolver tenantConfigResolver,
                                      OperaPostingClient operaPostingClient,
                                      ReservationRepository reservationRepo) {
        this.invoiceRepo = invoiceRepo;
        this.invoiceItemRepo = invoiceItemRepo;
        this.allocationRepo = allocationRepo;
        this.productRepo = productRepo;
        this.paymentTransactionService = paymentTransactionService;
        this.operaFiscalMappingService = operaFiscalMappingService;
        this.configurationService = configurationService;
        this.tenantConfigResolver = tenantConfigResolver;
        this.operaPostingClient = operaPostingClient;
        this.reservationRepo = reservationRepo;
    }

    @Transactional(readOnly = true)
    public OperaInvoicePostingPreview previewInvoice(Long invoiceId, OperaInvoicePostRequest request) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        List<InvoiceItem> items = invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(invoiceId);
        if (isFinalStayOperaInvoice(invoice, items)) {
            List<InvoiceItem> linked = linkInvoiceItemsToStaysForOpera(invoice, items);
            return previewFinalStayPerReservation(invoice, linked, request);
        }
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
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        List<InvoiceItem> items = invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(invoiceId);
        if (isFinalStayOperaInvoice(invoice, items)) {
            List<InvoiceItem> linked = linkInvoiceItemsToStaysForOpera(invoice, items);
            return previewFinalStayPerReservation(invoice, linked, request).payload();
        }
        return preparePosting(invoiceId, request, false).payload();
    }

    @Transactional(noRollbackFor = Exception.class)
    public OperaInvoicePostingResult postInvoice(Long invoiceId, OperaInvoicePostRequest request) {
        Invoice invoiceEarly = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        List<InvoiceItem> itemsEarly = invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(invoiceId);
        if (isFinalStayOperaInvoice(invoiceEarly, itemsEarly)) {
            List<InvoiceItem> linked = linkInvoiceItemsToStaysForOpera(invoiceEarly, itemsEarly);
            return postFinalStayPerReservation(invoiceEarly, linked, request);
        }

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
                    prepared.target().hotel().getChainCode(),
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

    @Transactional(noRollbackFor = Exception.class)
    public Invoice tryAutoPostInvoice(Long invoiceId) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        if (!isAutoPostingEligible(invoice)) {
            return invoice;
        }
        try {
            return postInvoice(invoiceId, null).invoice();
        } catch (RuntimeException ex) {
            log.warn("Automatic Opera/OHIP posting failed for invoice {}", invoiceId, ex);
            return invoiceRepo.findById(invoiceId).orElse(invoice);
        }
    }

    private static boolean isFinalStayOperaInvoice(Invoice invoice, List<InvoiceItem> items) {
        return invoice.getInvoiceType() == InvoiceType.INVOICE && !items.isEmpty();
    }

    /**
     * For final invoices, every Opera charge post must target a stay's {@code operaReservationId}.
     * Lines may omit {@link InvoiceItem#getReservationId()} if the invoice has {@link Invoice#getReservationRequestId()},
     * in which case stays on that request are mapped by line count (same order as lineNo) or by matching {@code productId}.
     */
    private List<InvoiceItem> linkInvoiceItemsToStaysForOpera(Invoice invoice, List<InvoiceItem> items) {
        long withReservation = items.stream().filter(i -> i.getReservationId() != null).count();
        if (withReservation == items.size()) {
            return items;
        }
        if (withReservation > 0) {
            throw new IllegalStateException(
                    "Final stay invoice mixes lines with and without reservationId; all lines need a stay link for Opera");
        }
        return inferReservationIdsFromRequest(invoice, items);
    }

    private List<InvoiceItem> inferReservationIdsFromRequest(Invoice invoice, List<InvoiceItem> items) {
        Long reqId = invoice.getReservationRequestId();
        if (reqId == null) {
            throw new IllegalStateException(
                    "Final invoice (INVOICE) must have every line linked to a reservation stay (reservationId) "
                            + "so charges post to Opera reservations created at check-in; payment was posted at check-in only. "
                            + "Set reservationRequestId on the invoice to map lines from the request's stays, or set reservationId on each line.");
        }
        List<Reservation> stays = reservationRepo.findByRequestId(reqId).stream()
                .filter(r -> r.getStatus() == null || !"CANCELLED".equalsIgnoreCase(r.getStatus().trim()))
                .sorted(Comparator.comparing(Reservation::getId))
                .toList();
        if (stays.isEmpty()) {
            throw new IllegalStateException("Reservation request " + reqId + " has no active stays to map invoice lines to");
        }
        List<InvoiceItem> ordered = items.stream()
                .sorted(Comparator.comparing(InvoiceItem::getLineNo, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        if (ordered.size() == stays.size()) {
            List<InvoiceItem> linked = new ArrayList<>(ordered.size());
            for (int i = 0; i < ordered.size(); i++) {
                linked.add(copyItemWithReservation(ordered.get(i), stays.get(i).getId()));
            }
            return linked;
        }

        List<Reservation> available = new ArrayList<>(stays);
        List<InvoiceItem> linked = new ArrayList<>();
        for (InvoiceItem item : ordered) {
            Long pid = item.getProductId();
            Reservation match = null;
            if (pid != null) {
                for (Reservation r : available) {
                    if (pid.equals(r.getProductId())) {
                        match = r;
                        break;
                    }
                }
            }
            if (match == null) {
                throw new IllegalStateException(
                        "Cannot map invoice line " + item.getLineNo() + " to a stay on reservation request " + reqId
                                + " (line count differs from stay count; align productId per stay or add reservationId on each line)");
            }
            available.remove(match);
            linked.add(copyItemWithReservation(item, match.getId()));
        }
        return linked;
    }

    private static InvoiceItem copyItemWithReservation(InvoiceItem item, Long reservationId) {
        return InvoiceItem.builder()
                .id(item.getId())
                .invoice(item.getInvoice())
                .lineNo(item.getLineNo())
                .reservationId(reservationId)
                .productId(item.getProductId())
                .productName(item.getProductName())
                .quantity(item.getQuantity())
                .unitPriceGross(item.getUnitPriceGross())
                .discountPercent(item.getDiscountPercent())
                .discountAmount(item.getDiscountAmount())
                .priceWithoutTax(item.getPriceWithoutTax())
                .tax1Percent(item.getTax1Percent())
                .tax2Percent(item.getTax2Percent())
                .tax1Amount(item.getTax1Amount())
                .tax2Amount(item.getTax2Amount())
                .nettPrice(item.getNettPrice())
                .grossAmount(item.getGrossAmount())
                .createdAt(item.getCreatedAt())
                .build();
    }

    private void persistInferredReservationLinks(List<InvoiceItem> linkedItems) {
        for (InvoiceItem linked : linkedItems) {
            if (linked.getId() == null || linked.getReservationId() == null) {
                continue;
            }
            invoiceItemRepo.findById(linked.getId()).ifPresent(persisted -> {
                if (persisted.getReservationId() == null) {
                    persisted.setReservationId(linked.getReservationId());
                    invoiceItemRepo.save(persisted);
                }
            });
        }
    }

    /**
     * Final stay invoices mirror only charges to OHIP; deposit (and its payment) is posted at check-in via
     * {@code /reservations/{id}/payments}. Other invoice types may still include payment rows when configured.
     */
    private static boolean shouldIncludeOperaPayments(Invoice invoice) {
        return invoice.getInvoiceType() != InvoiceType.INVOICE;
    }

    private OperaInvoicePostingPreview previewFinalStayPerReservation(Invoice invoice,
                                                                      List<InvoiceItem> items,
                                                                      OperaInvoicePostRequest request) {
        if (items.isEmpty()) {
            throw new IllegalStateException("Invoice has no items to post");
        }
        OperaHotel hotel = resolveHotelForStayInvoice(invoice, request);
        Long cashierId = resolveCashierId(request, hotel);
        Integer folioWindowNo = resolveFolioWindowNo(request, hotel);
        Map<Long, List<InvoiceItem>> byReservation = items.stream()
                .collect(Collectors.groupingBy(InvoiceItem::getReservationId, LinkedHashMap::new, Collectors.toList()));

        ArrayNode posts = objectMapper.createArrayNode();
        Long firstOhId = null;
        for (Map.Entry<Long, List<InvoiceItem>> e : byReservation.entrySet()) {
            Reservation stay = reservationRepo.findById(e.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("Reservation not found: " + e.getKey()));
            if (!invoice.getTenantId().equals(stay.getTenantId())) {
                throw new IllegalStateException("Reservation " + stay.getId() + " tenant mismatch for invoice");
            }
            Long ohId = stay.getOperaReservationId();
            if (ohId == null || ohId <= 0) {
                throw new IllegalStateException(
                        "Reservation " + stay.getId() + " has no operaReservationId; check in on OHIP first");
            }
            if (firstOhId == null) {
                firstOhId = ohId;
            }
            ResolvedTarget target = new ResolvedTarget(
                    OperaPostingTarget.RESERVATION, hotel, ohId, cashierId, folioWindowNo);
            posts.add(buildPayload(invoice, e.getValue(), List.of(), target, request, false));
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("perReservationChargePosts", posts);
        return new OperaInvoicePostingPreview(
                invoice,
                OperaPostingTarget.RESERVATION,
                hotel.getHotelCode(),
                firstOhId,
                cashierId,
                folioWindowNo,
                wrapper
        );
    }

    private OperaInvoicePostingResult postFinalStayPerReservation(Invoice invoice,
                                                                   List<InvoiceItem> items,
                                                                   OperaInvoicePostRequest request) {
        if (effectivePostingStatus(invoice) == OperaPostingStatus.POSTED
                && !Boolean.TRUE.equals(request != null ? request.getForce() : null)) {
            throw new IllegalStateException("Invoice is already posted to Opera; use force=true to repost");
        }

        OperaTenantConfigResolver.OperaResolvedConfig config = resolveConfig(invoice.getTenantId(), request);
        OperaHotel hotel = resolveHotelForStayInvoice(invoice, request);
        Long cashierId = resolveCashierId(request, hotel);
        Integer folioWindowNo = resolveFolioWindowNo(request, hotel);
        String chain = postingChainCode(hotel);

        Map<Long, List<InvoiceItem>> byReservation = items.stream()
                .collect(Collectors.groupingBy(InvoiceItem::getReservationId, LinkedHashMap::new, Collectors.toList()));

        ArrayNode requestPosts = objectMapper.createArrayNode();
        JsonNode lastResponse = null;
        try {
            invoice.setOperaErrorMessage(null);
            invoiceRepo.save(invoice);

            for (Map.Entry<Long, List<InvoiceItem>> e : byReservation.entrySet()) {
                Reservation stay = reservationRepo.findById(e.getKey())
                        .orElseThrow(() -> new IllegalArgumentException("Reservation not found: " + e.getKey()));
                if (!invoice.getTenantId().equals(stay.getTenantId())) {
                    throw new IllegalStateException("Reservation " + stay.getId() + " tenant mismatch for invoice");
                }
                Long ohId = stay.getOperaReservationId();
                if (ohId == null || ohId <= 0) {
                    throw new IllegalStateException(
                            "Reservation " + stay.getId() + " has no operaReservationId; check in on OHIP first");
                }
                ResolvedTarget target = new ResolvedTarget(
                        OperaPostingTarget.RESERVATION, hotel, ohId, cashierId, folioWindowNo);
                JsonNode payload = buildPayload(invoice, e.getValue(), List.of(), target, request, false);
                requestPosts.add(payload);
                invoice.setOperaLastRequestPayload(payload);
                invoiceRepo.save(invoice);
                lastResponse = operaPostingClient.postChargesAndPayments(
                        config, hotel.getHotelCode(), chain, ohId, payload);
            }

            invoice.setOperaPostingStatus(OperaPostingStatus.POSTED);
            invoice.setOperaPostedAt(OffsetDateTime.now());
            invoice.setOperaHotelCode(hotel.getHotelCode());
            if (byReservation.size() == 1) {
                Long onlyOh = reservationRepo.findById(byReservation.keySet().iterator().next())
                        .map(Reservation::getOperaReservationId)
                        .orElse(null);
                invoice.setOperaReservationId(onlyOh);
            } else {
                invoice.setOperaReservationId(null);
            }
            ObjectNode wrap = objectMapper.createObjectNode();
            wrap.set("perReservationChargePosts", requestPosts);
            invoice.setOperaLastRequestPayload(wrap);
            invoice.setOperaLastResponsePayload(lastResponse);
            invoice.setOperaErrorMessage(null);
            Invoice saved = invoiceRepo.save(invoice);
            persistInferredReservationLinks(items);
            return new OperaInvoicePostingResult(
                    saved,
                    OperaPostingTarget.RESERVATION,
                    hotel.getHotelCode(),
                    saved.getOperaReservationId(),
                    cashierId,
                    folioWindowNo,
                    wrap,
                    lastResponse
            );
        } catch (Exception ex) {
            invoice.setOperaPostingStatus(OperaPostingStatus.FAILED);
            invoice.setOperaErrorMessage(ex.getMessage());
            invoiceRepo.save(invoice);
            throw ex;
        }
    }

    private OperaHotel resolveHotelForStayInvoice(Invoice invoice, OperaInvoicePostRequest request) {
        String overrideHotelCode = normalizeHotelCode(request != null ? request.getHotelCode() : null);
        String invoiceHotelCode = normalizeHotelCode(invoice.getOperaHotelCode());
        String defaultHotelCode = tenantConfigResolver.findDefaultHotelCode(invoice.getTenantId()).orElse(null);
        String resolvedHotelCode = firstNonBlank(overrideHotelCode, invoiceHotelCode, defaultHotelCode);
        if (!StringUtils.hasText(resolvedHotelCode)) {
            throw new IllegalArgumentException("hotelCode is required for Opera final stay posting");
        }
        return configurationService.requireActiveHotel(invoice.getTenantId(), resolvedHotelCode);
    }

    private static String postingChainCode(OperaHotel hotel) {
        if (hotel == null || !StringUtils.hasText(hotel.getChainCode())) {
            return "";
        }
        return hotel.getChainCode().trim();
    }

    private boolean canAutoPostFinalStayPerReservation(Invoice invoice, List<InvoiceItem> items) {
        OperaHotel hotel = resolveHotelForStayInvoice(invoice, null);
        configurationService.requireActiveHotel(invoice.getTenantId(), hotel.getHotelCode());
        Map<Long, List<InvoiceItem>> byReservation = items.stream()
                .collect(Collectors.groupingBy(InvoiceItem::getReservationId));
        for (Long reservationId : byReservation.keySet()) {
            Reservation stay = reservationRepo.findById(reservationId)
                    .orElseThrow(() -> new IllegalStateException("Reservation not found: " + reservationId));
            if (stay.getOperaReservationId() == null || stay.getOperaReservationId() <= 0) {
                return false;
            }
            if (!invoice.getTenantId().equals(stay.getTenantId())) {
                return false;
            }
        }
        return true;
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
        JsonNode payload = buildPayload(
                invoice, items, allocations, target, request, shouldIncludeOperaPayments(invoice));
        return new PreparedPosting(invoice, target, payload);
    }

    private boolean isAutoPostingEligible(Invoice invoice) {
        if (invoice == null || invoice.getId() == null || invoice.getTenantId() == null) {
            return false;
        }
        if (invoice.getStatus() != InvoiceStatus.ISSUED) {
            return false;
        }
        if (effectivePostingStatus(invoice) == OperaPostingStatus.POSTED) {
            return false;
        }
        if (!hasResolvedOperaConfig(invoice.getTenantId())) {
            return false;
        }

        List<InvoiceItem> items = invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(invoice.getId());
        try {
            if (isFinalStayOperaInvoice(invoice, items)) {
                List<InvoiceItem> linked = linkInvoiceItemsToStaysForOpera(invoice, items);
                return canAutoPostFinalStayPerReservation(invoice, linked);
            }
        } catch (RuntimeException ex) {
            return false;
        }

        String invoiceHotelCode = normalizeHotelCode(invoice.getOperaHotelCode());
        Long invoiceReservationId = normalizePositiveLong(invoice.getOperaReservationId());
        OperaPostingTarget postingTarget = invoice.resolveOperaPostingTarget();

        try {
            if (postingTarget == OperaPostingTarget.RESERVATION) {
                if (!StringUtils.hasText(invoiceHotelCode) || invoiceReservationId == null) {
                    return false;
                }
                configurationService.requireActiveHotel(invoice.getTenantId(), invoiceHotelCode);
                return true;
            }

            if (StringUtils.hasText(invoiceHotelCode) && invoiceReservationId != null) {
                configurationService.requireActiveHotel(invoice.getTenantId(), invoiceHotelCode);
                return true;
            }

            String preferredHotelCode = firstNonBlank(
                    invoiceHotelCode,
                    tenantConfigResolver.findDefaultHotelCode(invoice.getTenantId()).orElse(null)
            );
            OperaInvoiceTypeRouting routing = configurationService.resolveRouting(
                    invoice.getTenantId(),
                    invoice.getInvoiceType(),
                    preferredHotelCode
            );
            if (routing.getReservationId() == null || routing.getReservationId() <= 0) {
                return false;
            }
            configurationService.requireActiveHotel(invoice.getTenantId(), routing.getHotelCode());
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean hasResolvedOperaConfig(Long tenantId) {
        try {
            tenantConfigResolver.resolve(tenantId);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
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

    private static String missingOperaChargeMappingMessage(Long tenantId, InvoiceItem item, String productType) {
        Long itemId = item != null ? item.getId() : null;
        Long productId = item != null ? item.getProductId() : null;
        Integer lineNo = item != null ? item.getLineNo() : null;
        String typeLabel = productType != null && !productType.isBlank() ? productType : "unknown";
        return "Opera charge mapping is missing for invoice item "
                + itemId + " (line " + lineNo + ", tenantId=" + tenantId
                + ", productId=" + productId + ", productType=" + typeLabel
                + "). Add an active opera_fiscal_charge_mapping for this product, product_type " + typeLabel
                + ", or a tenant default (product_id and product_type both null).";
    }

    private JsonNode buildPayload(Invoice invoice,
                                  List<InvoiceItem> items,
                                  List<InvoicePaymentAllocation> allocations,
                                  ResolvedTarget target,
                                  OperaInvoicePostRequest request,
                                  boolean includePayments) {
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
                    .orElseThrow(() -> new IllegalStateException(missingOperaChargeMappingMessage(
                            invoice.getTenantId(), item, product != null ? product.getProductType() : null)));

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
        if (includePayments) {
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
