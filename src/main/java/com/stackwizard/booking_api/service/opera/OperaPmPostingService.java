package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.dto.OperaDtos;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.BookingFunctionItem;
import com.stackwizard.booking_api.model.BookingOperaBlock;
import com.stackwizard.booking_api.model.BookingOperaPosting;
import com.stackwizard.booking_api.model.OperaCode;
import com.stackwizard.booking_api.model.OperaFiscalChargeMapping;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.repository.BookingFunctionItemRepository;
import com.stackwizard.booking_api.repository.BookingFunctionRepository;
import com.stackwizard.booking_api.repository.BookingOperaBlockRepository;
import com.stackwizard.booking_api.repository.BookingOperaPostingRepository;
import com.stackwizard.booking_api.repository.OperaHotelRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Catering items and space rental of a booking are charges on the Posting Master reservation of the hotel's OPERA
 * block. Only the difference to what was already posted goes to OPERA, so posting again after a change sends the delta
 * (negative for a reduction or a removed item).
 */
@Service
public class OperaPmPostingService {
    private static final Logger log = LoggerFactory.getLogger(OperaPmPostingService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BookingOperaBlockRepository blockRepo;
    private final BookingOperaPostingRepository postingRepo;
    private final OperaHotelRepository hotelRepo;
    private final BookingFunctionRepository functionRepo;
    private final BookingFunctionItemRepository itemRepo;
    private final ReservationRepository reservationRepo;
    private final ProductRepository productRepo;
    private final OperaFiscalMappingService fiscalMapping;
    private final OperaBlockSyncService blockSync;
    private final OperaPostingClient client;
    private final OperaTenantConfigResolver configResolver;

    public OperaPmPostingService(BookingOperaBlockRepository blockRepo,
                                 BookingOperaPostingRepository postingRepo,
                                 OperaHotelRepository hotelRepo,
                                 BookingFunctionRepository functionRepo,
                                 BookingFunctionItemRepository itemRepo,
                                 ReservationRepository reservationRepo,
                                 ProductRepository productRepo,
                                 OperaFiscalMappingService fiscalMapping,
                                 OperaBlockSyncService blockSync,
                                 OperaPostingClient client,
                                 OperaTenantConfigResolver configResolver) {
        this.blockRepo = blockRepo;
        this.postingRepo = postingRepo;
        this.hotelRepo = hotelRepo;
        this.functionRepo = functionRepo;
        this.itemRepo = itemRepo;
        this.reservationRepo = reservationRepo;
        this.productRepo = productRepo;
        this.fiscalMapping = fiscalMapping;
        this.blockSync = blockSync;
        this.client = client;
        this.configResolver = configResolver;
    }

    @Transactional(readOnly = true)
    public OperaDtos.BookingOpera view(Booking booking) {
        List<OperaDtos.BookingOperaBlockView> blocks = new ArrayList<>();
        for (BookingOperaBlock row : blockRepo.findByTenantIdAndBookingIdOrderByIdAsc(booking.getTenantId(), booking.getId())) {
            OperaHotel hotel = hotelRepo.findById(row.getOperaHotelId()).orElse(null);
            Optional<OperaCode> status = Optional.empty();
            boolean pickup = false;
            if (hotel != null && row.getStatusCode() != null) {
                OperaStatusGraph graph = new OperaStatusGraph(List.of());
                try {
                    graph = blockSync.graph(hotel);
                } catch (IllegalStateException ignored) {
                    // codes not imported yet
                }
                status = graph.find(row.getStatusCode());
                pickup = graph.allowsPickup(row.getStatusCode());
            }
            blocks.add(OperaDtos.BookingOperaBlockView.builder()
                    .id(row.getId())
                    .propertyTenantId(row.getPropertyTenantId())
                    .hotelCode(hotel != null ? hotel.getHotelCode() : null)
                    .requestId(row.getRequestId())
                    .blockId(row.getBlockId())
                    .blockCode(row.getBlockCode())
                    .statusCode(row.getStatusCode())
                    .statusLabel(status.map(OperaCode::getDescription).orElse(null))
                    .allowsPickup(pickup)
                    .pmReservationId(row.getPmReservationId())
                    .syncStatus(row.getSyncStatus())
                    .lastError(row.getLastError())
                    .syncedAt(row.getSyncedAt())
                    .dateFrom(row.getDateFrom())
                    .dateTo(row.getDateTo())
                    .build());
        }
        return OperaDtos.BookingOpera.builder()
                .blocks(blocks)
                .postings(postingRepo.findByTenantIdAndBookingIdOrderByIdAsc(booking.getTenantId(), booking.getId()))
                .pending(pending(booking))
                .build();
    }

    /** Charges whose current amount differs from what was posted, per OPERA-billed hotel block. */
    @Transactional(readOnly = true)
    public List<OperaDtos.PendingCharge> pending(Booking booking) {
        List<BookingFunction> functions = functionRepo.findForBooking(booking.getTenantId(), booking.getId());
        List<OperaDtos.PendingCharge> out = new ArrayList<>();
        for (BookingOperaBlock row : blockRepo.findByTenantIdAndBookingIdOrderByIdAsc(booking.getTenantId(), booking.getId())) {
            OperaHotel hotel = hotelRepo.findById(row.getOperaHotelId()).orElse(null);
            if (hotel == null || hotel.getBilling() != OperaHotel.Mode.OPERA || !StringUtils.hasText(row.getBlockId())) {
                continue;
            }
            out.addAll(pendingForBlock(booking, row, hotel, functions.stream()
                    .filter(f -> row.getPropertyTenantId().equals(f.getTenantId()))
                    .toList()));
        }
        return out;
    }

    private List<OperaDtos.PendingCharge> pendingForBlock(Booking booking, BookingOperaBlock row, OperaHotel hotel,
                                                         List<BookingFunction> functions) {
        Map<String, BigDecimal> posted = new HashMap<>();
        BigDecimal orphanItems = BigDecimal.ZERO;
        BigDecimal orphanRent = BigDecimal.ZERO;
        for (BookingOperaPosting p : postingRepo.findByBookingOperaBlockIdAndStatus(row.getId(), BookingOperaPosting.Status.POSTED)) {
            if (p.getSource() == BookingOperaPosting.Source.ITEM) {
                if (p.getBookingFunctionItemId() == null) {
                    orphanItems = orphanItems.add(p.getAmount());
                } else {
                    posted.merge("ITEM:" + p.getBookingFunctionItemId(), p.getAmount(), BigDecimal::add);
                }
            } else if (p.getBookingFunctionId() == null) {
                orphanRent = orphanRent.add(p.getAmount());
            } else {
                posted.merge("RENTAL:" + p.getBookingFunctionId(), p.getAmount(), BigDecimal::add);
            }
        }
        List<OperaDtos.PendingCharge> out = new ArrayList<>();
        List<Long> functionIds = functions.stream().map(BookingFunction::getId).toList();
        Map<Long, BookingFunction> byId = functions.stream().collect(Collectors.toMap(BookingFunction::getId, f -> f));
        if (!functionIds.isEmpty()) {
            List<BookingFunctionItem> items = itemRepo.findByTenantIdAndBookingFunctionIdIn(row.getPropertyTenantId(), functionIds);
            for (BookingFunctionItem item : items) {
                BigDecimal delta = money(item.getGrossAmount()).subtract(posted.getOrDefault("ITEM:" + item.getId(), BigDecimal.ZERO));
                if (delta.signum() != 0) {
                    BookingFunction function = byId.get(item.getBookingFunctionId());
                    String name = productName(item.getProductId());
                    String description = (function != null ? function.getName() + " · " : "")
                            + (StringUtils.hasText(item.getDescription()) ? item.getDescription() : name)
                            + " × " + item.getQty();
                    out.add(charge(booking, row, hotel, function, item.getId(), BookingOperaPosting.Source.ITEM,
                            description, item.getProductId(), delta));
                }
            }
            for (Reservation rent : reservationRepo.findByTenantIdAndBookingFunctionIdIn(row.getPropertyTenantId(), functionIds)) {
                if ("CANCELLED".equalsIgnoreCase(rent.getStatus())) {
                    continue;
                }
                BigDecimal delta = money(rent.getGrossAmount())
                        .subtract(posted.getOrDefault("RENTAL:" + rent.getBookingFunctionId(), BigDecimal.ZERO));
                if (delta.signum() != 0) {
                    BookingFunction function = byId.get(rent.getBookingFunctionId());
                    out.add(charge(booking, row, hotel, function, null, BookingOperaPosting.Source.RENTAL,
                            "Space rental · " + (function != null ? function.getName() : rent.getBookingFunctionId()),
                            rent.getProductId(), delta));
                }
            }
        }
        if (orphanItems.signum() != 0) {
            out.add(charge(booking, row, hotel, null, null, BookingOperaPosting.Source.ITEM, "Removed catering items",
                    null, orphanItems.negate()));
        }
        if (orphanRent.signum() != 0) {
            out.add(charge(booking, row, hotel, null, null, BookingOperaPosting.Source.RENTAL, "Removed space rental",
                    null, orphanRent.negate()));
        }
        return out;
    }

    private OperaDtos.PendingCharge charge(Booking booking, BookingOperaBlock row, OperaHotel hotel, BookingFunction function,
                                           Long itemId, BookingOperaPosting.Source source, String description,
                                           Long productId, BigDecimal amount) {
        Product product = productId == null ? null : productRepo.findById(productId).orElse(null);
        String productType = product != null ? product.getProductType() : null;
        Optional<OperaFiscalChargeMapping> mapping = fiscalMapping.resolveChargeMapping(row.getPropertyTenantId(), productId, productType)
                .or(() -> fiscalMapping.resolveChargeMapping(booking.getTenantId(), productId, productType));
        return OperaDtos.PendingCharge.builder()
                .blockRowId(row.getId())
                .hotelCode(hotel.getHotelCode())
                .functionId(function != null ? function.getId() : null)
                .itemId(itemId)
                .source(source)
                .description(description)
                .productId(productId)
                .trxCode(mapping.map(OperaFiscalChargeMapping::getTrxCode).orElse(null))
                .amount(amount)
                .problem(mapping.isPresent() ? null
                        : "No OPERA transaction code for " + (product != null ? product.getName() : "this charge")
                        + "; add an OPERA charge mapping")
                .build();
    }

    /**
     * Posts every pending charge to the Posting Master of its hotel block, creating the PM first. A block must be in an
     * OPERA status that allows pickup.
     */
    @Transactional
    public List<BookingOperaPosting> post(Booking booking) {
        List<OperaDtos.PendingCharge> pending = pending(booking);
        if (pending.isEmpty()) {
            return List.of();
        }
        List<String> problems = pending.stream().map(OperaDtos.PendingCharge::getProblem).filter(StringUtils::hasText)
                .distinct().toList();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems));
        }
        List<BookingOperaPosting> out = new ArrayList<>();
        Map<Long, List<OperaDtos.PendingCharge>> byBlock = pending.stream()
                .collect(Collectors.groupingBy(OperaDtos.PendingCharge::getBlockRowId));
        for (Map.Entry<Long, List<OperaDtos.PendingCharge>> entry : byBlock.entrySet()) {
            BookingOperaBlock row = blockRepo.findById(entry.getKey()).orElseThrow();
            OperaHotel hotel = hotelRepo.findById(row.getOperaHotelId()).orElseThrow();
            OperaStatusGraph graph = blockSync.graph(hotel);
            if (!graph.allowsPickup(row.getStatusCode())) {
                throw new IllegalStateException("OPERA block " + row.getBlockCode() + " is " + row.getStatusCode()
                        + "; charges can be posted once the block is in a status that allows pickup (e.g. definite)");
            }
            out.addAll(postBlock(booking, row, hotel, entry.getValue()));
        }
        return out;
    }

    private List<BookingOperaPosting> postBlock(Booking booking, BookingOperaBlock row, OperaHotel hotel,
                                                List<OperaDtos.PendingCharge> charges) {
        Long cashierId = hotel.getDefaultCashierId();
        if (cashierId == null || cashierId <= 0) {
            throw new IllegalStateException("Set the default cashier of OPERA hotel " + hotel.getHotelCode());
        }
        int folioWindow = hotel.getDefaultFolioWindowNo() != null && hotel.getDefaultFolioWindowNo() > 0
                ? hotel.getDefaultFolioWindowNo() : 1;
        String currency = booking.getCurrency() != null ? booking.getCurrency() : "EUR";
        OperaTenantConfigResolver.OperaResolvedConfig config = configResolver.resolve(hotel.getTenantId());
        String pmId = ensurePostingMaster(row, hotel, config);

        ObjectNode payload = MAPPER.createObjectNode();
        ObjectNode criteria = payload.putObject("criteria");
        criteria.put("postIt", false);
        criteria.put("cashierId", cashierId);
        ArrayNode lines = criteria.putArray("charges");
        for (OperaDtos.PendingCharge charge : charges) {
            ObjectNode line = lines.addObject();
            line.put("transactionCode", charge.getTrxCode());
            ObjectNode price = line.putObject("price");
            price.put("amount", charge.getAmount());
            price.put("currencyCode", currency);
            line.put("postingQuantity", 1);
            line.put("postingReference", row.getBlockCode());
            line.put("postingRemark", trim(charge.getDescription(), 200));
            line.put("applyRoutingInstructions", false);
            line.put("autoPosting", true);
            line.put("folioWindowNo", folioWindow);
            line.put("cashierId", cashierId);
        }
        JsonNode response = null;
        String error = null;
        try {
            response = client.postCharges(config, hotel.getHotelCode(), chain(hotel), Long.valueOf(pmId), payload);
        } catch (RuntimeException ex) {
            log.warn("OPERA PM posting failed for booking {} block {}: {}", booking.getId(), row.getBlockCode(), ex.getMessage());
            error = trim(ex.getMessage(), 2000);
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<BookingOperaPosting> saved = new ArrayList<>();
        for (OperaDtos.PendingCharge charge : charges) {
            saved.add(postingRepo.save(BookingOperaPosting.builder()
                    .tenantId(booking.getTenantId())
                    .bookingId(booking.getId())
                    .bookingOperaBlockId(row.getId())
                    .bookingFunctionId(charge.getFunctionId())
                    .bookingFunctionItemId(charge.getItemId())
                    .source(charge.getSource())
                    .description(charge.getDescription())
                    .trxCode(charge.getTrxCode())
                    .amount(charge.getAmount())
                    .currency(currency)
                    .status(error == null ? BookingOperaPosting.Status.POSTED : BookingOperaPosting.Status.FAILED)
                    .lastError(error)
                    .request(payload)
                    .response(response)
                    .postedAt(error == null ? now : null)
                    .build()));
        }
        return saved;
    }

    /** The block's Posting Master reservation; opens the block for pickup and lets OPERA create the PM when missing. */
    String ensurePostingMaster(BookingOperaBlock row, OperaHotel hotel, OperaTenantConfigResolver.OperaResolvedConfig config) {
        if (StringUtils.hasText(row.getPmReservationId())) {
            return row.getPmReservationId();
        }
        String base = "/blk/v1/hotels/" + hotel.getHotelCode() + "/blocks/" + row.getBlockId();
        ObjectNode open = MAPPER.createObjectNode();
        open.put("hotelId", hotel.getHotelCode());
        open.set("blockId", blockId(row));
        try {
            client.put(config, chain(hotel), hotel.getHotelCode(), base + "/openStatus", open);
        } catch (RuntimeException ex) {
            log.info("OPERA openStatus for block {} not applied (may already be open): {}", row.getBlockCode(), ex.getMessage());
        }
        String pmId = pmReservationId(client.get(config, chain(hotel), hotel.getHotelCode(),
                base + "/postingMaster/reservations", Map.of("postingmaster", List.of("true"))));
        if (pmId == null) {
            ObjectNode create = MAPPER.createObjectNode();
            ObjectNode criteria = create.putObject("criteria");
            criteria.put("hotelId", hotel.getHotelCode());
            criteria.set("blockId", blockId(row));
            criteria.putObject("responseInstruction").put("fetchFullReservation", false);
            client.post(config, chain(hotel), hotel.getHotelCode(), base + "/postingMaster", create);
            pmId = pmReservationId(client.get(config, chain(hotel), hotel.getHotelCode(),
                    base + "/postingMaster/reservations", Map.of()));
        }
        if (pmId == null) {
            throw new IllegalStateException("OPERA did not return a Posting Master reservation for block " + row.getBlockCode());
        }
        row.setPmReservationId(pmId);
        blockRepo.save(row);
        return pmId;
    }

    /** First reservation id of type Reservation in a PM reservations response. */
    static String pmReservationId(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject() && node.get("reservationIdList") != null && node.get("reservationIdList").isArray()) {
            String first = null;
            for (JsonNode id : node.get("reservationIdList")) {
                String value = id.path("id").asText(null);
                if (value != null && "Reservation".equalsIgnoreCase(id.path("type").asText())) {
                    return value;
                }
                if (first == null) {
                    first = value;
                }
            }
            if (first != null) {
                return first;
            }
        }
        for (JsonNode child : node) {
            if (child.isContainerNode()) {
                String found = pmReservationId(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String productName(Long productId) {
        return productId == null ? "Item" : productRepo.findById(productId).map(Product::getName).orElse("Item " + productId);
    }

    private static ObjectNode blockId(BookingOperaBlock row) {
        ObjectNode id = MAPPER.createObjectNode();
        id.put("type", "Block");
        id.put("idContext", "OPERA");
        id.put("id", row.getBlockId());
        return id;
    }

    private static String chain(OperaHotel hotel) {
        return StringUtils.hasText(hotel.getChainCode()) ? hotel.getChainCode().trim() : "";
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
