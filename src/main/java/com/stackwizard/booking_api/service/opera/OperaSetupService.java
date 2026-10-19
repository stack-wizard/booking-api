package com.stackwizard.booking_api.service.opera;

import com.stackwizard.booking_api.dto.BookingStatusDtos;
import com.stackwizard.booking_api.dto.OperaDtos;
import com.stackwizard.booking_api.model.CrmLookup;
import com.stackwizard.booking_api.model.OperaCode;
import com.stackwizard.booking_api.model.OperaCodeMap;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.model.PriceProfile;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceType;
import com.stackwizard.booking_api.repository.OperaCodeMapRepository;
import com.stackwizard.booking_api.repository.OperaCodeRepository;
import com.stackwizard.booking_api.repository.OperaFiscalChargeMappingRepository;
import com.stackwizard.booking_api.repository.OperaHotelRepository;
import com.stackwizard.booking_api.repository.PriceProfileRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.repository.ResourceTypeRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.BookingStatusService;
import com.stackwizard.booking_api.service.CrmLookupService;
import com.stackwizard.booking_api.service.TenantHierarchy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Prepares a hotel for OPERA-led sales in one go: imports the OPERA code lists, creates local room-type pools and
 * function spaces from them, makes the chain's event types and setup styles OPERA's, maps them 1:1, maps price
 * profiles to a usable room rate code, sets the default function-space rental code, and imports block statuses.
 * Safe to run again as a refresh.
 */
@Service
public class OperaSetupService {
    private final OperaHotelRepository hotelRepo;
    private final OperaCodeRepository codeRepo;
    private final OperaCodeMapRepository mapRepo;
    private final OperaCodeImportService importService;
    private final OperaRoomTypeSyncService roomTypeSyncService;
    private final ResourceRepository resourceRepo;
    private final ResourceTypeRepository resourceTypeRepo;
    private final PriceProfileRepository priceProfileRepo;
    private final OperaFiscalChargeMappingRepository chargeMappingRepo;
    private final CrmLookupService lookupService;
    private final BookingStatusService statusService;
    private final TenantHierarchy hierarchy;
    private final CrmAccessContext accessContext;
    private final TransactionTemplate tx;

    public OperaSetupService(OperaHotelRepository hotelRepo,
                             OperaCodeRepository codeRepo,
                             OperaCodeMapRepository mapRepo,
                             OperaCodeImportService importService,
                             OperaRoomTypeSyncService roomTypeSyncService,
                             ResourceRepository resourceRepo,
                             ResourceTypeRepository resourceTypeRepo,
                             PriceProfileRepository priceProfileRepo,
                             OperaFiscalChargeMappingRepository chargeMappingRepo,
                             CrmLookupService lookupService,
                             BookingStatusService statusService,
                             TenantHierarchy hierarchy,
                             CrmAccessContext accessContext,
                             PlatformTransactionManager transactionManager) {
        this.hotelRepo = hotelRepo;
        this.codeRepo = codeRepo;
        this.mapRepo = mapRepo;
        this.importService = importService;
        this.roomTypeSyncService = roomTypeSyncService;
        this.resourceRepo = resourceRepo;
        this.resourceTypeRepo = resourceTypeRepo;
        this.priceProfileRepo = priceProfileRepo;
        this.chargeMappingRepo = chargeMappingRepo;
        this.lookupService = lookupService;
        this.statusService = statusService;
        this.hierarchy = hierarchy;
        this.accessContext = accessContext;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public OperaDtos.SetupResult setup(Long hotelId) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        Long property = TenantResolver.requirePropertyTenantId();
        OperaHotel hotel = hotelRepo.findById(hotelId == null ? -1L : hotelId)
                .filter(h -> property.equals(h.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OPERA hotel not found: " + hotelId));
        List<String> warnings = new ArrayList<>();
        List<OperaDtos.ImportKindResult> codes = importService.importCodes(hotel);
        codes.stream().filter(r -> r.getError() != null)
                .forEach(r -> warnings.add(r.getKind() + " import failed: " + r.getError()));

        OperaRoomTypeSyncService.SyncResult rooms = roomTypeSyncService.sync(hotel);
        int[] spaces = tx.execute(status -> syncFunctionSpaces(hotel));
        Long org = hierarchy.orgIdOf(hotel.getTenantId());
        int eventTypes = tx.execute(status -> lookupService.alignWithOpera(org, CrmLookup.Kind.EVENT_TYPE,
                labels(hotel, OperaCode.Kind.EVENT_TYPE)));
        int setupStyles = tx.execute(status -> lookupService.alignWithOpera(org, CrmLookup.Kind.SETUP_STYLE,
                labels(hotel, OperaCode.Kind.SETUP_STYLE)));
        int maps = tx.execute(status -> {
            int n = 0;
            n += mapLookupCodes(hotel, OperaCodeMap.Kind.EVENT_TYPE, OperaCode.Kind.EVENT_TYPE);
            n += mapLookupCodes(hotel, OperaCodeMap.Kind.SETUP_STYLE, OperaCode.Kind.SETUP_STYLE);
            n += mapPriceProfiles(hotel, org, warnings);
            return n;
        });
        tx.executeWithoutResult(status -> ensureDefaultRentalCode(hotel));

        BookingStatusDtos.OperaImportResult statuses = null;
        try {
            statuses = statusService.importFromOpera(hotel.getId());
        } catch (IllegalStateException ex) {
            warnings.add("Booking statuses: " + ex.getMessage());
        }

        if (hotel.getDefaultCashierId() == null) {
            warnings.add("Set the default cashier of the hotel; charges cannot be posted to the Posting Master without it");
        }
        if (hotel.getBilling() == OperaHotel.Mode.OPERA
                && chargeMappingRepo.findFirstByTenantIdAndActiveTrueAndProductIdIsNullAndProductTypeIsNullOrderByPriorityAsc(hotel.getTenantId()).isEmpty()
                && chargeMappingRepo.findFirstByTenantIdAndActiveTrueAndProductIdIsNullAndProductTypeIsNullOrderByPriorityAsc(org).isEmpty()) {
            warnings.add("No default OPERA charge mapping (transaction code); catering cannot be posted until products are mapped");
        }
        if (hotel.getSpaceInventory() != OperaHotel.Mode.OPERA) {
            warnings.add("Function spaces are kept locally (space inventory LOCAL); functions are not sent as OPERA events");
        }
        return OperaDtos.SetupResult.builder()
                .codes(codes)
                .roomTypes(rooms)
                .spacesCreated(spaces[0])
                .spacesUpdated(spaces[1])
                .eventTypes(eventTypes)
                .setupStyles(setupStyles)
                .mapsUpserted(maps)
                .statuses(statuses)
                .warnings(warnings)
                .build();
    }

    /** Every active OPERA function space is a local meeting room with the same code, mapped to it. */
    int[] syncFunctionSpaces(OperaHotel hotel) {
        ResourceType type = resourceTypeRepo.findByCodeIgnoreCase("MEETING_ROOM")
                .orElseThrow(() -> new IllegalStateException("Resource type MEETING_ROOM is missing; run migrations first"));
        int created = 0;
        int updated = 0;
        for (OperaCode code : codeRepo.findByTenantIdAndOperaHotelIdAndKindOrderByCodeAsc(
                hotel.getTenantId(), hotel.getId(), OperaCode.Kind.FUNCTION_SPACE)) {
            if (!Boolean.TRUE.equals(code.getActive())) {
                continue;
            }
            String name = StringUtils.hasText(code.getDescription()) ? code.getDescription() : code.getCode();
            Resource resource = resourceRepo.findByTenantIdAndCode(hotel.getTenantId(), code.getCode()).orElse(null);
            if (resource == null) {
                resource = resourceRepo.save(Resource.builder()
                        .tenantId(hotel.getTenantId())
                        .resourceType(type)
                        .kind("EXACT")
                        .code(code.getCode())
                        .name(name)
                        .status("ACTIVE")
                        .displayOrder(0)
                        .canBookAlone(true)
                        .unitCount(1)
                        .capAdults(0)
                        .capChildren(0)
                        .capInfants(0)
                        .capTotal(0)
                        .build());
                created++;
            } else {
                resource.setName(name);
                resource.setStatus("ACTIVE");
                resource = resourceRepo.save(resource);
                updated++;
            }
            upsertMap(hotel, OperaCodeMap.Kind.FUNCTION_SPACE, resource.getId().toString(), code.getCode());
        }
        return new int[]{created, updated};
    }

    /**
     * When local code lists were aligned with OPERA, map each code to itself so the admin mapping screen is complete
     * and hotels that later rename a local code can override one row.
     */
    int mapLookupCodes(OperaHotel hotel, OperaCodeMap.Kind mapKind, OperaCode.Kind codeKind) {
        int n = 0;
        for (OperaCode code : codeRepo.findByTenantIdAndOperaHotelIdAndKindOrderByCodeAsc(
                hotel.getTenantId(), hotel.getId(), codeKind)) {
            if (!Boolean.TRUE.equals(code.getActive()) || !StringUtils.hasText(code.getCode())) {
                continue;
            }
            upsertMap(hotel, mapKind, code.getCode(), code.getCode());
            n++;
        }
        return n;
    }

    /**
     * Unmapped price profiles of the chain get a room rate code (prefer RATE1 / RACK). Already mapped rows are left
     * alone so a manual choice survives refresh.
     */
    int mapPriceProfiles(OperaHotel hotel, Long org, List<String> warnings) {
        Optional<String> rate = preferredRoomRate(hotel);
        if (rate.isEmpty()) {
            warnings.add("No active OPERA rate codes; price profiles were not mapped");
            return 0;
        }
        int n = 0;
        for (PriceProfile profile : priceProfileRepo.findByTenantIdOrderByIdAsc(org)) {
            String localRef = profile.getId().toString();
            Optional<OperaCodeMap> existing = mapRepo.findByTenantIdAndOperaHotelIdAndKindAndLocalRef(
                    hotel.getTenantId(), hotel.getId(), OperaCodeMap.Kind.RATE_CODE, localRef);
            if (existing.isPresent()) {
                continue;
            }
            upsertMap(hotel, OperaCodeMap.Kind.RATE_CODE, localRef, rate.get());
            n++;
        }
        return n;
    }

    private void ensureDefaultRentalCode(OperaHotel hotel) {
        OperaHotel row = hotelRepo.findById(hotel.getId()).orElse(hotel);
        if (!StringUtils.hasText(row.getDefaultRentalCode())) {
            row.setDefaultRentalCode("CUSTOM");
            hotelRepo.save(row);
            hotel.setDefaultRentalCode("CUSTOM");
        }
    }

    private Optional<String> preferredRoomRate(OperaHotel hotel) {
        List<OperaCode> rates = codeRepo.findByTenantIdAndOperaHotelIdAndKindOrderByCodeAsc(
                hotel.getTenantId(), hotel.getId(), OperaCode.Kind.RATE_CODE).stream()
                .filter(c -> Boolean.TRUE.equals(c.getActive()))
                .filter(c -> StringUtils.hasText(c.getCode()))
                .toList();
        for (String preferred : List.of("FJBB", "BARBB", "RATE1", "RATE2", "RACK")) {
            for (OperaCode code : rates) {
                if (preferred.equalsIgnoreCase(code.getCode())) {
                    return Optional.of(code.getCode());
                }
            }
        }
        for (OperaCode code : rates) {
            String cat = code.getAttrs() != null && code.getAttrs().has("rateCategory")
                    ? code.getAttrs().get("rateCategory").asText("") : "";
            if ("RACK".equalsIgnoreCase(cat)) {
                return Optional.of(code.getCode());
            }
        }
        return rates.stream().map(OperaCode::getCode).findFirst();
    }

    private void upsertMap(OperaHotel hotel, OperaCodeMap.Kind kind, String localRef, String operaCode) {
        OperaCodeMap map = mapRepo.findByTenantIdAndOperaHotelIdAndKindAndLocalRef(
                        hotel.getTenantId(), hotel.getId(), kind, localRef)
                .orElseGet(() -> OperaCodeMap.builder()
                        .tenantId(hotel.getTenantId())
                        .operaHotelId(hotel.getId())
                        .kind(kind)
                        .localRef(localRef)
                        .build());
        map.setOperaCode(operaCode);
        mapRepo.save(map);
    }

    private Map<String, String> labels(OperaHotel hotel, OperaCode.Kind kind) {
        Map<String, String> out = new LinkedHashMap<>();
        for (OperaCode code : codeRepo.findByTenantIdAndOperaHotelIdAndKindOrderByCodeAsc(hotel.getTenantId(), hotel.getId(), kind)) {
            if (Boolean.TRUE.equals(code.getActive())) {
                String label = StringUtils.hasText(code.getDescription()) ? code.getDescription() : code.getCode();
                out.put(code.getCode(), label);
            }
        }
        return out;
    }
}
