package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.LocationNode;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class ReservationConfirmationEmailRenderer {
    private static final String TEMPLATE_PATH = "templates/reservation-confirmation-email.html";
    private static final Pattern BULLET_SPLIT_PATTERN = Pattern.compile("\\r?\\n|\\s*[;]\\s*|\\s*[\\u2022]\\s*");
    private static final String DEFAULT_ARRIVAL_NOTE =
            "Please arrive at the reception desk upon arrival and present this confirmation.";

    public RenderedEmail render(ReservationRequest request,
                                List<Reservation> reservations,
                                Map<Long, Product> productsById,
                                PaymentService.RequestPaymentSummary paymentSummary,
                                TenantEmailConfigResolver.EmailResolvedConfig emailConfig) {
        try {
            List<Reservation> orderedReservations = reservations.stream()
                    .sorted(Comparator
                            .comparing(Reservation::getStartsAt, Comparator.nullsLast(LocalDateTime::compareTo))
                            .thenComparing(Reservation::getId, Comparator.nullsLast(Long::compareTo)))
                    .toList();
            Locale locale = resolveLocale(emailConfig.locale());
            String template = new ClassPathResource(TEMPLATE_PATH).getContentAsString(StandardCharsets.UTF_8);
            String currency = resolveCurrency(orderedReservations);
            BigDecimal subtotal = money(paymentSummary != null ? paymentSummary.totalAmount() : null);
            BigDecimal depositAmount = money(paymentSummary != null ? paymentSummary.dueNowAmount() : null);
            BigDecimal remainingAtVenue = subtotal.subtract(depositAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

            Map<String, String> values = new LinkedHashMap<>();
            values.put("PREHEADER", esc("Your booking has been received and confirmed."));
            values.put("BRAND_NAME", esc(firstNonBlank(emailConfig.brandName(), "Booking")));
            values.put("BRAND_NAME_UPPER", esc(firstNonBlank(emailConfig.brandName(), "Booking").toUpperCase(locale)));
            values.put("BOOKING_CARDS", buildBookingCards(orderedReservations, productsById, locale));
            values.put("PRICE_ROWS", buildPriceRows(orderedReservations, productsById, currency, locale));
            values.put("SUBTOTAL_AMOUNT", esc(formatMoney(subtotal, currency)));
            values.put("DEPOSIT_LABEL", esc(resolveDepositLabel(paymentSummary)));
            values.put("DEPOSIT_AMOUNT", esc(formatMoney(depositAmount, currency)));
            values.put("REMAINING_AMOUNT", esc(formatMoney(remainingAtVenue, currency)));
            values.put("INCLUDES_BLOCK", buildIncludesBlock(orderedReservations, productsById));
            values.put("ARRIVAL_NOTE_BLOCK", buildArrivalNoteBlock(emailConfig));
            values.put("FOOTER_LOCATION", esc(firstNonBlank(emailConfig.footerLocation(), "")));
            values.put("CONTACT_LINE", buildContactLine(emailConfig));

            String subject = "Reservation confirmed - " + firstNonBlank(emailConfig.brandName(), "Booking")
                    + " #" + (request.getId() != null ? request.getId().toString() : "-");
            String plainText = buildPlainText(
                    request,
                    orderedReservations,
                    productsById,
                    paymentSummary,
                    remainingAtVenue,
                    currency,
                    locale,
                    emailConfig
            );
            return new RenderedEmail(subject, plainText, applyTemplate(template, values));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to render reservation confirmation email", ex);
        }
    }

    private String buildBookingCards(List<Reservation> reservations,
                                     Map<Long, Product> productsById,
                                     Locale locale) {
        StringBuilder cards = new StringBuilder();
        for (Reservation reservation : reservations) {
            Product product = resolveProduct(reservation, productsById);
            String title = resolveTitle(reservation, product);
            String subtitle = resolveSubtitle(reservation, product, title, locale);

            cards.append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"margin:0 0 16px; border:1px solid #2c2b20; background:#0a0b07;\">")
                    .append("<tr><td style=\"padding:22px 24px 18px; border-bottom:1px solid #202116;\">")
                    .append("<div style=\"font-size:11px; letter-spacing:3px; text-transform:uppercase; color:#ae9e73; margin-bottom:10px;\">Package</div>")
                    .append("<div style=\"font-family:Georgia, 'Times New Roman', serif; font-size:34px; line-height:1.2; color:#f6efe0;\">").append(esc(title)).append("</div>");
            if (StringUtils.hasText(subtitle)) {
                cards.append("<div style=\"margin-top:6px; font-size:12px; letter-spacing:2px; text-transform:uppercase; color:#8c886f;\">")
                        .append(subtitle)
                        .append("</div>");
            }
            cards.append("</td></tr>")
                    .append("<tr><td style=\"padding:18px 24px 8px;\">")
                    .append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">")
                    .append("<tr>")
                    .append(buildDetailCell("Date", formatDate(reservation, locale)))
                    .append(buildDetailCell("Hours", formatTimeRange(reservation)))
                    .append("</tr>")
                    .append("<tr>")
                    .append(buildDetailCell("Capacity", buildGuestLabel(reservation)))
                    .append(buildDetailCell("Reservation ID", reservation.getId() != null ? "#" + reservation.getId() : "-"))
                    .append("</tr>")
                    .append("</table>")
                    .append("</td></tr>")
                    .append("</table>");
        }
        return cards.toString();
    }

    private String buildPriceRows(List<Reservation> reservations,
                                  Map<Long, Product> productsById,
                                  String currency,
                                  Locale locale) {
        if (reservations.isEmpty()) {
            return "<tr><td style=\"padding:14px 20px; color:#8c886f;\">Reservation</td><td align=\"right\" style=\"padding:14px 20px; color:#8c886f;\">"
                    + esc(formatMoney(BigDecimal.ZERO, currency)) + "</td></tr>";
        }

        StringBuilder rows = new StringBuilder();
        for (Reservation reservation : reservations) {
            Product product = resolveProduct(reservation, productsById);
            String title = resolveTitle(reservation, product);
            String label = title + " - " + formatDate(reservation, locale);
            rows.append("<tr>")
                    .append("<td style=\"padding:16px 20px; border-bottom:1px solid #202116; color:#f6efe0; font-size:14px;\">")
                    .append(esc(label))
                    .append("</td>")
                    .append("<td align=\"right\" style=\"padding:16px 20px; border-bottom:1px solid #202116; color:#f6efe0; font-size:14px; white-space:nowrap;\">")
                    .append(esc(formatMoney(reservationTotal(reservation), currency)))
                    .append("</td>")
                    .append("</tr>");
        }
        return rows.toString();
    }

    private String buildIncludesBlock(List<Reservation> reservations,
                                      Map<Long, Product> productsById) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (Reservation reservation : reservations) {
            Product product = resolveProduct(reservation, productsById);
            List<String> highlights = extractHighlights(product != null ? product.getDescription() : null);
            if (highlights.isEmpty()) {
                continue;
            }
            sections.putIfAbsent(resolveTitle(reservation, product), highlights);
        }
        if (sections.isEmpty()) {
            return "";
        }

        StringBuilder html = new StringBuilder();
        html.append("<tr><td style=\"padding:16px 32px 0;\">")
                .append("<div style=\"font-size:11px; letter-spacing:4px; text-transform:uppercase; color:#ae9e73; margin-bottom:14px;\">Includes</div>");

        boolean multipleSections = sections.size() > 1;
        for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
            if (multipleSections) {
                html.append("<div style=\"margin:0 0 8px; color:#f6efe0; font-size:15px; font-weight:bold;\">")
                        .append(esc(entry.getKey()))
                        .append("</div>");
            }
            html.append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"margin:0 0 14px;\">");
            for (String item : entry.getValue()) {
                html.append("<tr>")
                        .append("<td valign=\"top\" style=\"width:16px; padding:0 0 8px; color:#ae9e73;\">&bull;</td>")
                        .append("<td style=\"padding:0 0 8px; color:#d7d0bf; font-size:14px; line-height:1.6;\">")
                        .append(esc(item))
                        .append("</td>")
                        .append("</tr>");
            }
            html.append("</table>");
        }

        html.append("</td></tr>");
        return html.toString();
    }

    private String buildArrivalNoteBlock(TenantEmailConfigResolver.EmailResolvedConfig emailConfig) {
        String note = firstNonBlank(emailConfig.arrivalNote(), DEFAULT_ARRIVAL_NOTE);
        if (!StringUtils.hasText(note)) {
            return "";
        }
        return "<tr><td style=\"padding:8px 32px 0;\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"border-left:2px solid #c6a55a; background:#12100a;\">"
                + "<tr><td style=\"padding:18px 20px; color:#d7d0bf; font-size:14px; line-height:1.7;\">"
                + esc(note)
                + "</td></tr></table></td></tr>";
    }

    private String buildContactLine(TenantEmailConfigResolver.EmailResolvedConfig emailConfig) {
        if (!StringUtils.hasText(emailConfig.supportEmail())) {
            return "";
        }
        return "<div style=\"margin-top:10px; font-size:12px; color:#8c886f;\">Questions? Contact us at "
                + "<a href=\"mailto:" + escAttribute(emailConfig.supportEmail().trim()) + "\" style=\"color:#d8bf84; text-decoration:none;\">"
                + esc(emailConfig.supportEmail().trim())
                + "</a></div>";
    }

    private String buildPlainText(ReservationRequest request,
                                  List<Reservation> reservations,
                                  Map<Long, Product> productsById,
                                  PaymentService.RequestPaymentSummary paymentSummary,
                                  BigDecimal remainingAtVenue,
                                  String currency,
                                  Locale locale,
                                  TenantEmailConfigResolver.EmailResolvedConfig emailConfig) {
        StringBuilder text = new StringBuilder();
        text.append("Reservation Confirmed").append(System.lineSeparator())
                .append(firstNonBlank(emailConfig.brandName(), "Booking")).append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("Reservation: ")
                .append(request.getId() != null ? "#" + request.getId() : "-")
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        for (Reservation reservation : reservations) {
            Product product = resolveProduct(reservation, productsById);
            text.append("- ").append(resolveTitle(reservation, product)).append(System.lineSeparator())
                    .append("  Date: ").append(formatDate(reservation, locale)).append(System.lineSeparator())
                    .append("  Hours: ").append(formatTimeRange(reservation)).append(System.lineSeparator())
                    .append("  Capacity: ").append(buildGuestLabel(reservation)).append(System.lineSeparator())
                    .append("  Reservation ID: ").append(reservation.getId() != null ? "#" + reservation.getId() : "-").append(System.lineSeparator());
        }

        BigDecimal subtotal = money(paymentSummary != null ? paymentSummary.totalAmount() : null);
        BigDecimal depositAmount = money(paymentSummary != null ? paymentSummary.dueNowAmount() : null);

        text.append(System.lineSeparator())
                .append("Price summary").append(System.lineSeparator());
        for (Reservation reservation : reservations) {
            Product product = resolveProduct(reservation, productsById);
            text.append("- ")
                    .append(resolveTitle(reservation, product))
                    .append(": ")
                    .append(formatMoney(reservationTotal(reservation), currency))
                    .append(System.lineSeparator());
        }
        text.append("Subtotal: ").append(formatMoney(subtotal, currency)).append(System.lineSeparator())
                .append(resolveDepositLabel(paymentSummary)).append(": ").append(formatMoney(depositAmount, currency)).append(System.lineSeparator())
                .append("Remaining at venue: ").append(formatMoney(remainingAtVenue, currency)).append(System.lineSeparator());

        List<String> highlights = new ArrayList<>();
        for (Reservation reservation : reservations) {
            Product product = resolveProduct(reservation, productsById);
            for (String item : extractHighlights(product != null ? product.getDescription() : null)) {
                if (!highlights.contains(item)) {
                    highlights.add(item);
                }
            }
        }
        if (!highlights.isEmpty()) {
            text.append(System.lineSeparator()).append("Includes").append(System.lineSeparator());
            for (String item : highlights) {
                text.append("- ").append(item).append(System.lineSeparator());
            }
        }

        String note = firstNonBlank(emailConfig.arrivalNote(), DEFAULT_ARRIVAL_NOTE);
        if (StringUtils.hasText(note)) {
            text.append(System.lineSeparator()).append(note).append(System.lineSeparator());
        }
        if (StringUtils.hasText(emailConfig.supportEmail())) {
            text.append(System.lineSeparator())
                    .append("Questions? Contact us at ")
                    .append(emailConfig.supportEmail().trim())
                    .append(System.lineSeparator());
        }
        return text.toString().trim();
    }

    private String buildDetailCell(String label, String value) {
        return "<td width=\"50%\" valign=\"top\" style=\"padding:0 12px 18px 0;\">"
                + "<div style=\"font-size:11px; letter-spacing:2px; text-transform:uppercase; color:#7f7a63; margin-bottom:6px;\">"
                + esc(label)
                + "</div>"
                + "<div style=\"font-size:28px; line-height:1.3; color:#f6efe0; font-family:Georgia, 'Times New Roman', serif;\">"
                + esc(value)
                + "</div>"
                + "</td>";
    }

    private String resolveDepositLabel(PaymentService.RequestPaymentSummary paymentSummary) {
        BigDecimal paidAmount = money(paymentSummary != null ? paymentSummary.paidAmount() : null);
        return paidAmount.compareTo(BigDecimal.ZERO) > 0 ? "Deposit paid" : "Deposit amount";
    }

    private String resolveTitle(Reservation reservation, Product product) {
        Resource resource = reservation.getRequestedResource();
        return firstNonBlank(
                resource != null ? resource.getName() : null,
                product != null ? product.getName() : null,
                "Reservation"
        );
    }

    private String resolveSubtitle(Reservation reservation, Product product, String title, Locale locale) {
        Resource resource = reservation.getRequestedResource();
        LocationNode location = resource != null ? resource.getLocation() : null;
        List<String> parts = new ArrayList<>();
        if (product != null && StringUtils.hasText(product.getName()) && !sameText(product.getName(), title)) {
            parts.add(product.getName().toUpperCase(locale));
        }
        if (resource != null && StringUtils.hasText(resource.getCode()) && !sameText(resource.getCode(), title)) {
            parts.add(resource.getCode().toUpperCase(locale));
        } else if (resource != null && StringUtils.hasText(resource.getName()) && !sameText(resource.getName(), title)) {
            parts.add(resource.getName().toUpperCase(locale));
        }
        if (location != null && StringUtils.hasText(location.getName()) && parts.stream().noneMatch(v -> sameText(v, location.getName()))) {
            parts.add(location.getName().toUpperCase(locale));
        }
        if (parts.isEmpty()) {
            return "";
        }
        StringBuilder subtitle = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                subtitle.append(" &bull; ");
            }
            subtitle.append(esc(parts.get(i)));
        }
        return subtitle.toString();
    }

    private List<String> extractHighlights(String description) {
        if (!StringUtils.hasText(description)) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String chunk : BULLET_SPLIT_PATTERN.split(description.trim())) {
            String normalized = normalizeHighlight(chunk);
            if (StringUtils.hasText(normalized) && !items.contains(normalized)) {
                items.add(normalized);
            }
        }
        if (items.isEmpty()) {
            String normalized = normalizeHighlight(description);
            if (StringUtils.hasText(normalized)) {
                items.add(normalized);
            }
        }
        return items;
    }

    private String normalizeHighlight(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        while (normalized.startsWith("-") || normalized.startsWith("*")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }

    private Product resolveProduct(Reservation reservation, Map<Long, Product> productsById) {
        if (reservation.getProductId() != null) {
            Product product = productsById.get(reservation.getProductId());
            if (product != null) {
                return product;
            }
        }
        Resource resource = reservation.getRequestedResource();
        if (resource != null) {
            return resource.getProduct();
        }
        return null;
    }

    private String formatDate(Reservation reservation, Locale locale) {
        LocalDateTime startsAt = reservation.getStartsAt();
        LocalDateTime endsAt = reservation.getEndsAt();
        if (startsAt == null) {
            return "-";
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM d, uuuu", locale);
        LocalDate startDate = startsAt.toLocalDate();
        LocalDate endDate = endsAt != null ? endsAt.toLocalDate() : startDate;
        if (!Objects.equals(startDate, endDate)) {
            return formatter.format(startDate) + " - " + formatter.format(endDate);
        }
        return formatter.format(startDate);
    }

    private String formatTimeRange(Reservation reservation) {
        if (reservation.getStartsAt() == null || reservation.getEndsAt() == null) {
            return "-";
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        return formatter.format(reservation.getStartsAt()) + " - " + formatter.format(reservation.getEndsAt());
    }

    private String buildGuestLabel(Reservation reservation) {
        int adults = safeCount(reservation.getAdults());
        int children = safeCount(reservation.getChildren());
        int infants = safeCount(reservation.getInfants());
        int guests = adults + children;
        if (guests <= 0) {
            guests = adults + children + infants;
        }
        if (guests <= 0) {
            return "-";
        }
        String label = guests + (guests == 1 ? " person" : " persons");
        if (infants > 0 && guests != infants) {
            label += " + " + infants + (infants == 1 ? " infant" : " infants");
        }
        return label;
    }

    private int safeCount(Integer value) {
        return value != null ? Math.max(value, 0) : 0;
    }

    private BigDecimal reservationTotal(Reservation reservation) {
        BigDecimal total = reservation.getGrossAmount();
        if (total == null && reservation.getUnitPrice() != null && reservation.getQty() != null) {
            total = reservation.getUnitPrice().multiply(BigDecimal.valueOf(reservation.getQty()));
        }
        return money(total);
    }

    private String resolveCurrency(List<Reservation> reservations) {
        return reservations.stream()
                .map(Reservation::getCurrency)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .findFirst()
                .orElse("EUR");
    }

    private Locale resolveLocale(String rawLocale) {
        if (!StringUtils.hasText(rawLocale)) {
            return Locale.ENGLISH;
        }
        Locale locale = Locale.forLanguageTag(rawLocale.trim().replace('_', '-'));
        return StringUtils.hasText(locale.getLanguage()) ? locale : Locale.ENGLISH;
    }

    private String formatMoney(BigDecimal amount, String currency) {
        return money(amount).setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + firstNonBlank(currency, "EUR");
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private boolean sameText(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private String applyTemplate(String template, Map<String, String> values) {
        String output = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            output = output.replace("[[" + entry.getKey() + "]]", entry.getValue());
        }
        return output;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String esc(String value) {
        return HtmlUtils.htmlEscape(value != null ? value : "", StandardCharsets.UTF_8.name());
    }

    private String escAttribute(String value) {
        return esc(value).replace("\"", "&quot;");
    }

    public record RenderedEmail(String subject, String plainText, String html) {
    }
}
