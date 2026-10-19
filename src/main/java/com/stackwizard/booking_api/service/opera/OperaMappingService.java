package com.stackwizard.booking_api.service.opera;

import com.stackwizard.booking_api.dto.OperaDtos;
import com.stackwizard.booking_api.model.CrmLookup;
import com.stackwizard.booking_api.model.OperaCode;
import com.stackwizard.booking_api.model.OperaCodeMap;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.repository.OperaCodeMapRepository;
import com.stackwizard.booking_api.repository.OperaCodeRepository;
import com.stackwizard.booking_api.repository.OperaHotelRepository;
import com.stackwizard.booking_api.repository.PriceProfileRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.CrmLookupService;
import com.stackwizard.booking_api.service.ResourceSetupCapacityService;
import com.stackwizard.booking_api.service.TenantHierarchy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/** OPERA hotel modes, the imported OPERA code catalog and the local → OPERA code mapping of the selected hotel. */
@Service
public class OperaMappingService {
    private final OperaHotelRepository hotelRepo;
    private final OperaCodeRepository codeRepo;
    private final OperaCodeMapRepository mapRepo;
    private final OperaCodeImportService importService;
    private final OperaRoomTypeSyncService roomTypeSyncService;
    private final ResourceRepository resourceRepo;
    private final PriceProfileRepository priceProfileRepo;
    private final CrmLookupService lookupService;
    private final TenantHierarchy hierarchy;
    private final CrmAccessContext accessContext;

    public OperaMappingService(OperaHotelRepository hotelRepo,
                               OperaCodeRepository codeRepo,
                               OperaCodeMapRepository mapRepo,
                               OperaCodeImportService importService,
                               OperaRoomTypeSyncService roomTypeSyncService,
                               ResourceRepository resourceRepo,
                               PriceProfileRepository priceProfileRepo,
                               CrmLookupService lookupService,
                               TenantHierarchy hierarchy,
                               CrmAccessContext accessContext) {
        this.hotelRepo = hotelRepo;
        this.codeRepo = codeRepo;
        this.mapRepo = mapRepo;
        this.importService = importService;
        this.roomTypeSyncService = roomTypeSyncService;
        this.resourceRepo = resourceRepo;
        this.priceProfileRepo = priceProfileRepo;
        this.lookupService = lookupService;
        this.hierarchy = hierarchy;
        this.accessContext = accessContext;
    }

    public List<OperaHotel> hotels() {
        return hotelRepo.findByTenantIdOrderByHotelCodeAscIdAsc(TenantResolver.requirePropertyTenantId());
    }

    @Transactional
    public OperaHotel updateModes(Long hotelId, OperaDtos.ModesRequest request) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        OperaHotel hotel = requireHotel(hotelId);
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (request.getSpaceInventory() != null) {
            hotel.setSpaceInventory(request.getSpaceInventory());
        }
        if (request.getRoomInventory() != null) {
            hotel.setRoomInventory(request.getRoomInventory());
        }
        if (request.getBilling() != null) {
            hotel.setBilling(request.getBilling());
        }
        if (request.getDefaultMarketCode() != null) {
            hotel.setDefaultMarketCode(blankToNull(request.getDefaultMarketCode()));
        }
        if (request.getDefaultSourceCode() != null) {
            hotel.setDefaultSourceCode(blankToNull(request.getDefaultSourceCode()));
        }
        if (request.getDefaultNonDeductReservationType() != null) {
            hotel.setDefaultNonDeductReservationType(blankToNull(request.getDefaultNonDeductReservationType()));
        }
        if (request.getDefaultDeductReservationType() != null) {
            hotel.setDefaultDeductReservationType(blankToNull(request.getDefaultDeductReservationType()));
        }
        if (request.getDefaultRentalCode() != null) {
            hotel.setDefaultRentalCode(blankToNull(request.getDefaultRentalCode()));
        }
        if (request.getDefaultOriginCode() != null) {
            hotel.setDefaultOriginCode(blankToNull(request.getDefaultOriginCode()));
        }
        if (request.getDefaultBlockOwnerCode() != null) {
            hotel.setDefaultBlockOwnerCode(blankToNull(request.getDefaultBlockOwnerCode()));
        }
        return hotelRepo.save(hotel);
    }

    public List<OperaDtos.ImportKindResult> importCodes(Long hotelId) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        OperaHotel hotel = requireHotel(hotelId);
        List<OperaDtos.ImportKindResult> results = importService.importCodes(hotel);
        boolean roomsOk = results.stream()
                .anyMatch(r -> r.getKind() == OperaCode.Kind.ROOM_TYPE && r.getError() == null);
        if (roomsOk) {
            roomTypeSyncService.sync(hotel);
        }
        return results;
    }

    @Transactional
    public OperaRoomTypeSyncService.SyncResult syncRoomTypes(Long hotelId) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        return roomTypeSyncService.sync(requireHotel(hotelId));
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public List<OperaCode> codes(Long hotelId, OperaCode.Kind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        OperaHotel hotel = requireHotel(hotelId);
        return codeRepo.findByTenantIdAndOperaHotelIdAndKindOrderByCodeAsc(hotel.getTenantId(), hotel.getId(), kind);
    }

    public List<OperaCodeMap> maps(Long hotelId) {
        OperaHotel hotel = requireHotel(hotelId);
        return mapRepo.findByTenantIdAndOperaHotelIdOrderByKindAscLocalRefAsc(hotel.getTenantId(), hotel.getId());
    }

    /** Returns empty when the mapping was removed. */
    @Transactional
    public Optional<OperaCodeMap> saveMap(Long hotelId, OperaDtos.MapRequest request) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        OperaHotel hotel = requireHotel(hotelId);
        if (request == null || request.getKind() == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (!StringUtils.hasText(request.getLocalRef())) {
            throw new IllegalArgumentException("localRef is required");
        }
        if (!StringUtils.hasText(request.getOperaCode())) {
            mapRepo.findByTenantIdAndOperaHotelIdAndKindAndLocalRef(hotel.getTenantId(), hotel.getId(), request.getKind(),
                    request.getLocalRef().trim()).ifPresent(mapRepo::delete);
            return Optional.empty();
        }
        String localRef = requireLocalRef(hotel, request.getKind(), request.getLocalRef().trim());
        Optional<OperaCodeMap> existing = mapRepo.findByTenantIdAndOperaHotelIdAndKindAndLocalRef(
                hotel.getTenantId(), hotel.getId(), request.getKind(), localRef);
        String operaCode = request.getOperaCode().trim();
        codeRepo.findByTenantIdAndOperaHotelIdAndKindAndCode(hotel.getTenantId(), hotel.getId(), request.getKind().codeKind(), operaCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown OPERA " + request.getKind() + " code " + operaCode
                        + " for hotel " + hotel.getHotelCode() + "; import OPERA codes first"));
        OperaCodeMap map = existing.orElseGet(() -> OperaCodeMap.builder()
                .tenantId(hotel.getTenantId())
                .operaHotelId(hotel.getId())
                .kind(request.getKind())
                .localRef(localRef)
                .build());
        map.setOperaCode(operaCode);
        return Optional.of(mapRepo.save(map));
    }

    private String requireLocalRef(OperaHotel hotel, OperaCodeMap.Kind kind, String localRef) {
        Long org = hierarchy.orgIdOf(hotel.getTenantId());
        return switch (kind) {
            case FUNCTION_SPACE -> {
                Long id = parseId(localRef, "resource");
                resourceRepo.findByIdWithType(id)
                        .filter(r -> hotel.getTenantId().equals(r.getTenantId()))
                        .filter(ResourceSetupCapacityService::isFunctionSpace)
                        .orElseThrow(() -> new IllegalArgumentException("Space " + localRef + " is not a function space of this hotel"));
                yield id.toString();
            }
            case ROOM_TYPE -> {
                Long id = parseId(localRef, "resource");
                resourceRepo.findByIdWithType(id)
                        .filter(r -> hotel.getTenantId().equals(r.getTenantId()))
                        .filter(ResourceSetupCapacityService::isRoomType)
                        .orElseThrow(() -> new IllegalArgumentException("Room type " + localRef + " is not a room pool of this hotel"));
                yield id.toString();
            }
            case RATE_CODE -> {
                Long id = parseId(localRef, "price profile");
                priceProfileRepo.findByIdAndTenantId(id, org)
                        .filter(p -> p.getPropertyTenantId() == null || p.getPropertyTenantId().equals(hotel.getTenantId()))
                        .orElseThrow(() -> new IllegalArgumentException("Price profile " + localRef + " is not available for this hotel"));
                yield id.toString();
            }
            case EVENT_TYPE -> lookupService.requireCode(org, CrmLookup.Kind.EVENT_TYPE, localRef, null);
            case SETUP_STYLE -> lookupService.requireCode(org, CrmLookup.Kind.SETUP_STYLE, localRef, null);
        };
    }

    private OperaHotel requireHotel(Long hotelId) {
        Long property = TenantResolver.requirePropertyTenantId();
        return hotelRepo.findById(hotelId == null ? -1L : hotelId)
                .filter(h -> property.equals(h.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OPERA hotel not found: " + hotelId));
    }

    private static Long parseId(String value, String what) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid " + what + " id: " + value);
        }
    }
}
