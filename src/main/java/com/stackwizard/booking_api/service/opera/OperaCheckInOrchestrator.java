package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.config.BookingOperaProperties;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.model.OperaCheckInLineStatus;
import com.stackwizard.booking_api.model.OperaDepositPostStatus;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.InvoiceRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.service.InvoiceService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * When {@link BookingOperaProperties.CheckIn#enabled}, runs before local deposit void/final draft:
 * create Opera reservation, check in, persist {@link Reservation#getOperaReservationId()} /
 * {@link ReservationRequest#getOperaProfileId()}, then post check-in deposit via OHIP
 * {@link OperaPostingClient#postPayment} (standalone payments API — not invoice {@code chargesAndPayments}).
 * <p>
 * Request payloads follow common OHIP JSON shapes; tune codes via {@link BookingOperaProperties#getReservation()}
 * and hotel-level deposit trx/method on {@link OperaHotel}. Property-specific adjustments may be required.
 */
@Service
public class OperaCheckInOrchestrator {

    private final BookingOperaProperties bookingOperaProperties;
    private final OperaPostingClient operaPostingClient;
    private final OperaTenantConfigResolver tenantConfigResolver;
    private final OperaPostingConfigurationService configurationService;
    private final InvoiceService invoiceService;
    private final InvoiceRepository invoiceRepo;
    private final ReservationRequestRepository reservationRequestRepository;
    private final OperaCheckInProgressService checkInProgressService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OperaCheckInOrchestrator(BookingOperaProperties bookingOperaProperties,
                                    OperaPostingClient operaPostingClient,
                                    OperaTenantConfigResolver tenantConfigResolver,
                                    OperaPostingConfigurationService configurationService,
                                    InvoiceService invoiceService,
                                    InvoiceRepository invoiceRepo,
                                    ReservationRequestRepository reservationRequestRepository,
                                    OperaCheckInProgressService checkInProgressService) {
        this.bookingOperaProperties = bookingOperaProperties;
        this.operaPostingClient = operaPostingClient;
        this.tenantConfigResolver = tenantConfigResolver;
        this.configurationService = configurationService;
        this.invoiceService = invoiceService;
        this.invoiceRepo = invoiceRepo;
        this.reservationRequestRepository = reservationRequestRepository;
        this.checkInProgressService = checkInProgressService;
    }

    public void runIfEnabled(ReservationRequest request, List<Reservation> reservations) {
        if (!bookingOperaProperties.getCheckIn().isEnabled()) {
            return;
        }
        if (request == null || reservations == null || reservations.isEmpty()) {
            return;
        }
        Long tenantId = request.getTenantId();
        OperaTenantConfigResolver.OperaResolvedConfig config = tenantConfigResolver.resolve(tenantId);
        String hotelCodeUpper = tenantConfigResolver.findDefaultHotelCode(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "Opera default hotel code is missing on tenant PMS config for check-in"));
        OperaHotel hotel = configurationService.requireActiveHotel(tenantId, hotelCodeUpper);
        String chainCode = normalizeChain(hotel.getChainCode());

        if (!allNonCancelledHaveOperaRoomId(reservations)) {
            throw new IllegalStateException(
                    "Opera check-in is enabled: set OHIP room id (resource.opera_room_id) on each booked resource");
        }

        List<Reservation> active = reservations.stream()
                .filter(r -> !"CANCELLED".equalsIgnoreCase(r.getStatus()))
                .toList();
        if (active.isEmpty()) {
            return;
        }

        BookingOperaProperties.ReservationTemplate tmpl = bookingOperaProperties.getReservation();

        Long firstOperaReservationIdForDeposit = null;
        List<String> lineErrors = new ArrayList<>();
        for (Reservation stay : active) {
            JsonNode createResp = null;
            Long operaReservationId = stay.getOperaReservationId();
            String roomId = stay.getRequestedResource().getOperaRoomId().trim();
            try {
                if (operaReservationId == null) {
                    JsonNode createBody = buildCreateReservationPayload(request, stay, hotel.getHotelCode(), tmpl);
                    createResp = operaPostingClient.postCreateReservation(
                            config, chainCode, hotel.getHotelCode(), createBody);
                    operaReservationId = requireReservationId(createResp, "create reservation");
                    checkInProgressService.recordReservationCreated(stay.getId(), operaReservationId);
                }
                if (firstOperaReservationIdForDeposit == null) {
                    firstOperaReservationIdForDeposit = operaReservationId;
                }
                if (stay.getOperaCheckInStatus() != OperaCheckInLineStatus.CHECKIN_COMPLETE) {
                    JsonNode checkInBody = buildCheckInPayload(roomId);
                    JsonNode checkInResp = operaPostingClient.postCheckIn(
                            config, chainCode, hotel.getHotelCode(), operaReservationId, checkInBody);
                    checkInProgressService.recordCheckInSuccess(stay.getId());
                    String profileId = firstProfileId(checkInResp);
                    if (!StringUtils.hasText(profileId) && createResp != null) {
                        profileId = firstProfileId(createResp);
                    }
                    if (StringUtils.hasText(profileId)) {
                        checkInProgressService.mergeOperaProfileId(request.getId(), profileId);
                    }
                }
            } catch (RuntimeException ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                checkInProgressService.recordCheckInFailure(stay.getId(), msg);
                lineErrors.add("reservation line " + stay.getId() + ": " + msg);
            }
        }

        if (!lineErrors.isEmpty()) {
            throw new IllegalStateException("Opera check-in failed: " + String.join("; ", lineErrors));
        }

        BigDecimal depositTotal = sumActiveDepositGross(request.getId());
        ReservationRequest requestFresh = reservationRequestRepository.findById(request.getId())
                .orElseThrow(() -> new IllegalStateException("Reservation request not found: " + request.getId()));

        if (depositTotal.compareTo(BigDecimal.ZERO) <= 0) {
            checkInProgressService.recordDepositSkipped(request.getId());
            return;
        }

        if (requestFresh.getOperaDepositPostStatus() == OperaDepositPostStatus.POSTED) {
            return;
        }

        if (firstOperaReservationIdForDeposit == null) {
            throw new IllegalStateException("No Opera reservation id available for deposit payment");
        }
        if (!StringUtils.hasText(hotel.getCheckinDepositPaymentMethodCode())) {
            throw new IllegalStateException(
                    "Opera hotel " + hotel.getHotelCode() + " is missing checkinDepositPaymentMethodCode");
        }
        JsonNode paymentBody = buildDepositPaymentPayload(
                requestFresh.getId(),
                requestFresh,
                hotel,
                firstOperaReservationIdForDeposit,
                depositTotal,
                resolveCurrency(active));
        try {
            operaPostingClient.postPayment(
                    config, chainCode, hotel.getHotelCode(), firstOperaReservationIdForDeposit, paymentBody);
            checkInProgressService.recordDepositPosted(request.getId());
        } catch (RuntimeException ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            checkInProgressService.recordDepositFailed(request.getId(), msg);
            throw new IllegalStateException("Opera deposit payment failed: " + msg, ex);
        }
    }

    private static boolean allNonCancelledHaveOperaRoomId(List<Reservation> reservations) {
        for (Reservation r : reservations) {
            if ("CANCELLED".equalsIgnoreCase(r.getStatus())) {
                continue;
            }
            if (r.getRequestedResource() == null
                    || !StringUtils.hasText(r.getRequestedResource().getOperaRoomId())) {
                return false;
            }
        }
        return true;
    }

    private BigDecimal sumActiveDepositGross(Long requestId) {
        BigDecimal total = BigDecimal.ZERO;
        for (Invoice inv : invoiceService.findByRequestId(requestId)) {
            if (inv.getInvoiceType() != InvoiceType.DEPOSIT || inv.getStornoId() != null) {
                continue;
            }
            if (invoiceService.hasReversalChildForSourceInvoice(inv.getId(), InvoiceType.DEPOSIT)) {
                continue;
            }
            total = total.add(zeroSafe(inv.getTotalGross()));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zeroSafe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String resolveCurrency(List<Reservation> stays) {
        for (Reservation s : stays) {
            if (StringUtils.hasText(s.getCurrency())) {
                return s.getCurrency().trim();
            }
        }
        return "EUR";
    }

    private JsonNode buildCheckInPayload(String roomId) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode reservation = objectMapper.createObjectNode();
        reservation.put("roomId", roomId);
        reservation.put("ignoreWarnings", true);
        reservation.put("stopCheckin", false);
        reservation.put("printRegistration", false);
        root.set("reservation", reservation);
        ArrayNode fetch = objectMapper.createArrayNode();
        fetch.add("ReservationDetail");
        root.set("fetchReservationInstruction", fetch);
        root.put("includeNotifications", true);
        return root;
    }

    /**
     * Opera {@code postingReference} / {@code comments} for the check-in deposit payment should use the deposit
     * document's fiscal folio ({@link Invoice#getFiscalFolioNo()} from OFIS), not internal invoice numbers.
     * Reloads each deposit row from the DB so folio is visible if fiscalization committed earlier (possibly outside
     * this transaction). If no folio is available yet, falls back to confirmation code or {@code REQ-{id}} — not
     * invoice number.
     */
    private String resolveDepositPostingReference(Long requestId, ReservationRequest request) {
        List<Invoice> active = new ArrayList<>();
        for (Invoice inv : invoiceService.findByRequestId(requestId)) {
            if (inv.getInvoiceType() != InvoiceType.DEPOSIT || inv.getStornoId() != null) {
                continue;
            }
            if (invoiceService.hasReversalChildForSourceInvoice(inv.getId(), InvoiceType.DEPOSIT)) {
                continue;
            }
            active.add(inv);
        }
        active.sort(Comparator.comparing(Invoice::getId));
        for (Invoice inv : active) {
            if (inv.getId() == null) {
                continue;
            }
            Invoice fresh = invoiceRepo.findById(inv.getId()).orElse(inv);
            if (StringUtils.hasText(fresh.getFiscalFolioNo())) {
                return fresh.getFiscalFolioNo().trim();
            }
        }
        return StringUtils.hasText(request.getConfirmationCode())
                ? request.getConfirmationCode().trim()
                : "REQ-" + request.getId();
    }

    private JsonNode buildDepositPaymentPayload(Long requestId,
                                                ReservationRequest request,
                                                OperaHotel hotel,
                                                Long operaReservationId,
                                                BigDecimal amount,
                                                String currency) {
        Long cashierId = hotel.getDefaultCashierId();
        if (cashierId == null || cashierId <= 0) {
            throw new IllegalStateException("Opera hotel defaultCashierId is required for check-in deposit payment");
        }
        int folio = hotel.getDefaultFolioWindowNo() != null && hotel.getDefaultFolioWindowNo() > 0
                ? hotel.getDefaultFolioWindowNo()
                : 1;

        ObjectNode criteria = objectMapper.createObjectNode();
        criteria.put("hotelId", hotel.getHotelCode());

        ObjectNode paymentMethod = objectMapper.createObjectNode();
        paymentMethod.put("paymentMethod", hotel.getCheckinDepositPaymentMethodCode().trim());
        paymentMethod.put("folioView", folio);
        criteria.set("paymentMethod", paymentMethod);

        ObjectNode postingAmount = objectMapper.createObjectNode();
        postingAmount.put("amount", amount.setScale(2, RoundingMode.HALF_UP));
        postingAmount.put("currencyCode", currency);
        criteria.set("postingAmount", postingAmount);

        String ref = resolveDepositPostingReference(requestId, request);
        criteria.put("postingReference", ref);
        criteria.put("comments", ref);
        criteria.put("folioWindowNo", folio);
        criteria.put("cashierId", cashierId);

        if (StringUtils.hasText(hotel.getCheckinDepositPaymentTrxCode())) {
            criteria.put("transactionCode", hotel.getCheckinDepositPaymentTrxCode().trim());
        }

        ObjectNode reservationIdObj = objectMapper.createObjectNode();
        reservationIdObj.put("type", "Reservation");
        reservationIdObj.put("id", String.valueOf(operaReservationId));
        criteria.set("reservationId", reservationIdObj);

        ObjectNode root = objectMapper.createObjectNode();
        root.set("criteria", criteria);
        root.put("advanceDeposit", false);
        return root;
    }

    /**
     * OHIP {@code POST /rsv/v1/hotels/{hotelId}/reservations} body aligned with working tenant samples:
     * {@code reservations.reservation[]} with {@code roomStay.roomRates[]}, {@code reservationGuests},
     * {@code reservationPaymentMethods}.
     */
    private JsonNode buildCreateReservationPayload(ReservationRequest request,
                                                   Reservation stay,
                                                   String hotelCode,
                                                   BookingOperaProperties.ReservationTemplate tmpl) {
        String roomId = stay.getRequestedResource().getOperaRoomId().trim();
        LocalDate arrival = stay.getStartsAt() != null
                ? stay.getStartsAt().toLocalDate()
                : LocalDate.now();
        LocalDate departure = stay.getEndsAt() != null
                ? stay.getEndsAt().toLocalDate()
                : arrival.plusDays(1);

        String[] name = splitName(firstNonBlank(
                stay.getCustomerName(),
                request.getCustomerName()));

        String arrivalStr = arrival.toString();
        String departureStr = departure.toString();
        String currency = StringUtils.hasText(stay.getCurrency()) ? stay.getCurrency().trim() : "EUR";
        int adults = zeroSafe(stay.getAdults(), 1);
        int children = zeroSafe(stay.getChildren(), 0);

        ObjectNode sourceOfSale = objectMapper.createObjectNode();
        sourceOfSale.put("sourceType", nullToDefault(tmpl.getSourceOfSaleType(), "WLK"));
        sourceOfSale.put("sourceCode", nullToDefault(tmpl.getSourceOfSaleCode(), "WLK"));

        ObjectNode rateDay = objectMapper.createObjectNode();
        ObjectNode base = objectMapper.createObjectNode();
        base.put("amountBeforeTax", "0");
        base.put("currencyCode", currency);
        rateDay.set("base", base);
        rateDay.put("shareDistributionInstruction", "Full");
        ObjectNode rateDayTotal = objectMapper.createObjectNode();
        rateDayTotal.put("amountBeforeTax", "0");
        rateDay.set("total", rateDayTotal);
        rateDay.put("start", arrivalStr);
        rateDay.put("end", departureStr);
        ArrayNode rateArr = objectMapper.createArrayNode();
        rateArr.add(rateDay);
        ObjectNode rates = objectMapper.createObjectNode();
        rates.set("rate", rateArr);

        ObjectNode rateRowTotal = objectMapper.createObjectNode();
        rateRowTotal.put("amountBeforeTax", "0");
        ObjectNode rrGuestCounts = objectMapper.createObjectNode();
        rrGuestCounts.put("adults", String.valueOf(adults));
        rrGuestCounts.put("children", String.valueOf(children));

        ObjectNode rateRow = objectMapper.createObjectNode();
        rateRow.set("total", rateRowTotal);
        rateRow.set("rates", rates);
        rateRow.set("guestCounts", rrGuestCounts);
        rateRow.put("roomType", nullToDefault(tmpl.getRoomType(), "PM"));
        rateRow.put("ratePlanCode", nullToDefault(tmpl.getRatePlanCode(), "RATETEST"));
        rateRow.put("start", arrivalStr);
        rateRow.put("end", departureStr);
        rateRow.put("suppressRate", tmpl.isSuppressRate());
        rateRow.put("marketCode", nullToDefault(tmpl.getMarketCode(), "PKG"));
        rateRow.put("sourceCode", nullToDefault(tmpl.getSourceCode(), "LNG"));
        rateRow.put("numberOfUnits", "1");
        rateRow.put("pseudoRoom", tmpl.isPseudoRoom());
        rateRow.put("roomTypeCharged", nullToDefault(tmpl.getRoomTypeCharged(), tmpl.getRoomType()));
        rateRow.put("roomId", roomId);
        rateRow.put("houseUseOnly", false);
        rateRow.put("complimentary", false);
        rateRow.put("fixedRate", true);
        rateRow.put("discountAllowed", false);
        rateRow.put("bogoDiscount", false);

        ArrayNode roomRates = objectMapper.createArrayNode();
        roomRates.add(rateRow);

        ObjectNode rsGuestCounts = objectMapper.createObjectNode();
        rsGuestCounts.put("adults", String.valueOf(adults));
        rsGuestCounts.put("children", String.valueOf(children));

        ObjectNode guarantee = objectMapper.createObjectNode();
        guarantee.put("guaranteeCode", nullToDefault(tmpl.getGuaranteeCode(), "NON"));

        ObjectNode roomStay = objectMapper.createObjectNode();
        roomStay.set("roomRates", roomRates);
        roomStay.set("guestCounts", rsGuestCounts);
        roomStay.put("arrivalDate", arrivalStr);
        roomStay.put("departureDate", departureStr);
        roomStay.set("guarantee", guarantee);
        roomStay.put("roomNumberLocked", tmpl.isRoomNumberLocked());
        roomStay.put("printRate", false);

        ObjectNode personEntry = objectMapper.createObjectNode();
        personEntry.put("givenName", name[0]);
        personEntry.put("surname", name[1]);
        personEntry.put("nameType", "Primary");
        ArrayNode personNames = objectMapper.createArrayNode();
        personNames.add(personEntry);

        ObjectNode customer = objectMapper.createObjectNode();
        customer.set("personName", personNames);
        customer.put("language", nullToDefault(tmpl.getCustomerLanguage(), "E"));

        ObjectNode profile = objectMapper.createObjectNode();
        profile.set("customer", customer);
        profile.put("profileType", "Guest");

        ObjectNode profileInfo = objectMapper.createObjectNode();
        profileInfo.set("profile", profile);

        ObjectNode resGuest = objectMapper.createObjectNode();
        resGuest.set("profileInfo", profileInfo);
        resGuest.put("primary", true);

        ArrayNode reservationGuests = objectMapper.createArrayNode();
        reservationGuests.add(resGuest);

        ObjectNode paymentRow = objectMapper.createObjectNode();
        paymentRow.put("paymentMethod", nullToDefault(tmpl.getPaymentMethodCode(), "CA"));
        paymentRow.put("folioView", nullToDefault(tmpl.getPaymentFolioView(), "1"));
        ArrayNode reservationPaymentMethods = objectMapper.createArrayNode();
        reservationPaymentMethods.add(paymentRow);

        ObjectNode res = objectMapper.createObjectNode();
        res.set("sourceOfSale", sourceOfSale);
        res.set("roomStay", roomStay);
        res.set("reservationGuests", reservationGuests);
        res.set("reservationPaymentMethods", reservationPaymentMethods);

        if (StringUtils.hasText(request.getConfirmationCode()) || request.getId() != null) {
            String note = StringUtils.hasText(request.getConfirmationCode())
                    ? request.getConfirmationCode().trim()
                    : ("Request " + request.getId());
            ObjectNode commentObj = objectMapper.createObjectNode();
            ObjectNode text = objectMapper.createObjectNode();
            text.put("value", "Booking check-in: " + note);
            commentObj.set("text", text);
            commentObj.put("commentTitle", "General Notes");
            commentObj.put("notificationLocation", "RESERVATION");
            commentObj.put("type", "GEN");
            commentObj.put("internal", false);
            ObjectNode commentWrap = objectMapper.createObjectNode();
            commentWrap.set("comment", commentObj);
            ArrayNode comments = objectMapper.createArrayNode();
            comments.add(commentWrap);
            res.set("comments", comments);
        }

        res.put("hotelId", hotelCode);
        res.put("roomStayReservation", true);
        res.put("reservationStatus", "Reserved");
        res.put("computedReservationStatus", nullToDefault(tmpl.getComputedReservationStatus(), "DueIn"));
        res.put("walkIn", false);
        res.put("printRate", false);
        res.put("preRegistered", false);
        res.put("upgradeEligible", false);
        res.put("allowAutoCheckin", false);
        res.put("hasOpenFolio", false);
        res.put("allowMobileCheckout", false);
        res.put("allowMobileViewFolio", false);
        res.put("allowPreRegistration", false);
        res.put("optedForCommunication", false);

        ArrayNode reservationList = objectMapper.createArrayNode();
        reservationList.add(res);
        ObjectNode reservationsWrap = objectMapper.createObjectNode();
        reservationsWrap.set("reservation", reservationList);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("reservations", reservationsWrap);
        return root;
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.hasText(a)) {
            return a;
        }
        return b;
    }

    private static String nullToDefault(String val, String def) {
        return StringUtils.hasText(val) ? val.trim() : def;
    }

    private static int zeroSafe(Integer v, int dft) {
        return v != null ? v : dft;
    }

    private static String[] splitName(String full) {
        if (!StringUtils.hasText(full)) {
            return new String[]{"Guest", "Booking"};
        }
        String t = full.trim();
        int sp = t.indexOf(' ');
        if (sp < 0) {
            return new String[]{t, "-"};
        }
        return new String[]{t.substring(0, sp), t.substring(sp + 1).trim()};
    }

    private static String normalizeChain(String chainCode) {
        if (!StringUtils.hasText(chainCode)) {
            return "";
        }
        return chainCode.trim();
    }

    private Long requireReservationId(JsonNode root, String step) {
        Long id = extractReservationIdFromLinks(root);
        if (id != null) {
            return id;
        }
        id = firstLongAtField(root, "reservationId");
        if (id != null) {
            return id;
        }
        id = firstNumericReservationIdDeep(root, 0);
        if (id != null) {
            return id;
        }
        throw new IllegalStateException("Could not read Opera reservationId from " + step + " response");
    }

    private Long extractReservationIdFromLinks(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode links = root.get("links");
        if (links == null || !links.isArray()) {
            return null;
        }
        for (JsonNode link : links) {
            JsonNode hrefNode = link.get("href");
            if (hrefNode == null || !hrefNode.isTextual()) {
                continue;
            }
            String href = hrefNode.asText();
            String marker = "/reservations/";
            int pos = href.indexOf(marker);
            if (pos < 0) {
                continue;
            }
            String tail = href.substring(pos + marker.length());
            int end = tail.indexOf('/');
            String idPart = end < 0 ? tail : tail.substring(0, end);
            if (!StringUtils.hasText(idPart) || !idPart.chars().allMatch(Character::isDigit)) {
                continue;
            }
            try {
                return Long.parseLong(idPart);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private Long firstNumericReservationIdDeep(JsonNode node, int depth) {
        if (node == null || depth > 40) {
            return null;
        }
        if (node.isObject()) {
            var id = node.get("reservationId");
            Long v = parseLongNode(id);
            if (v != null) {
                return v;
            }
            var fields = node.properties();
            for (var e : fields) {
                Long found = firstNumericReservationIdDeep(e.getValue(), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode c : node) {
                Long found = firstNumericReservationIdDeep(c, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Long firstLongAtField(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return parseLongNode(node.get(field));
    }

    private static Long parseLongNode(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isIntegralNumber()) {
            return n.longValue();
        }
        if (n.isTextual()) {
            try {
                return Long.parseLong(n.asText().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private String firstProfileId(JsonNode node) {
        if (node == null) {
            return null;
        }
        String direct = textAt(node, "profileId");
        if (StringUtils.hasText(direct)) {
            return direct.trim();
        }
        direct = textAt(node, "profileID");
        if (StringUtils.hasText(direct)) {
            return direct.trim();
        }
        return findProfileIdDeep(node, 0);
    }

    private String findProfileIdDeep(JsonNode node, int depth) {
        if (node == null || depth > 40) {
            return null;
        }
        if (node.isObject()) {
            JsonNode profileIdList = node.get("profileIdList");
            if (profileIdList != null && profileIdList.isArray()) {
                for (JsonNode el : profileIdList) {
                    if (el != null && el.has("id")) {
                        JsonNode idNode = el.get("id");
                        if (idNode != null && idNode.isTextual() && StringUtils.hasText(idNode.asText())) {
                            return idNode.asText().trim();
                        }
                        if (idNode != null && idNode.isIntegralNumber()) {
                            return String.valueOf(idNode.longValue());
                        }
                    }
                }
            }
            String t = textAt(node, "profileId");
            if (StringUtils.hasText(t)) {
                return t.trim();
            }
            t = textAt(node, "profileID");
            if (StringUtils.hasText(t)) {
                return t.trim();
            }
            for (var e : node.properties()) {
                String f = findProfileIdDeep(e.getValue(), depth + 1);
                if (StringUtils.hasText(f)) {
                    return f;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode c : node) {
                String f = findProfileIdDeep(c, depth + 1);
                if (StringUtils.hasText(f)) {
                    return f;
                }
            }
        }
        return null;
    }

    private static String textAt(JsonNode obj, String field) {
        if (obj == null || !obj.isObject()) {
            return null;
        }
        JsonNode v = obj.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }
}
