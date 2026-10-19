package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.model.OperaCode;
import com.stackwizard.booking_api.model.OperaCodeMap;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceType;
import com.stackwizard.booking_api.repository.OperaCodeMapRepository;
import com.stackwizard.booking_api.repository.OperaCodeRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.repository.ResourceTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Turns imported OPERA {@code ROOM_TYPE} codes into local POOL resources (and keeps the mapping).
 * Pseudo / meeting-room types are skipped — those are not sellable guest rooms.
 */
@Service
public class OperaRoomTypeSyncService {
    private final OperaCodeRepository codeRepo;
    private final OperaCodeMapRepository mapRepo;
    private final ResourceRepository resourceRepo;
    private final ResourceTypeRepository resourceTypeRepo;

    public OperaRoomTypeSyncService(OperaCodeRepository codeRepo,
                                    OperaCodeMapRepository mapRepo,
                                    ResourceRepository resourceRepo,
                                    ResourceTypeRepository resourceTypeRepo) {
        this.codeRepo = codeRepo;
        this.mapRepo = mapRepo;
        this.resourceRepo = resourceRepo;
        this.resourceTypeRepo = resourceTypeRepo;
    }

    public record SyncResult(int created, int updated, int mapped, int skipped) {
    }

    @Transactional
    public SyncResult sync(OperaHotel hotel) {
        ResourceType roomType = resourceTypeRepo.findByCodeIgnoreCase("ROOM")
                .orElseThrow(() -> new IllegalStateException("Resource type ROOM is missing; run migrations first"));
        int created = 0;
        int updated = 0;
        int mapped = 0;
        int skipped = 0;
        List<OperaCode> codes = codeRepo.findByTenantIdAndOperaHotelIdAndKindOrderByCodeAsc(
                hotel.getTenantId(), hotel.getId(), OperaCode.Kind.ROOM_TYPE);
        for (OperaCode code : codes) {
            if (!Boolean.TRUE.equals(code.getActive()) || skip(code)) {
                skipped++;
                continue;
            }
            int units = unitsOf(code);
            String name = StringUtils.hasText(code.getDescription()) ? code.getDescription() : code.getCode();
            Resource resource = resourceRepo.findByTenantIdAndCode(hotel.getTenantId(), code.getCode())
                    .orElse(null);
            if (resource == null) {
                resource = Resource.builder()
                        .tenantId(hotel.getTenantId())
                        .resourceType(roomType)
                        .kind("POOL")
                        .code(code.getCode())
                        .name(name)
                        .status("ACTIVE")
                        .displayOrder(0)
                        .canBookAlone(true)
                        .unitCount(1)
                        .poolTotalUnits(units)
                        .capAdults(0)
                        .capChildren(0)
                        .capInfants(0)
                        .capTotal(0)
                        .operaRoomId(code.getCode())
                        .build();
                resource = resourceRepo.save(resource);
                created++;
            } else {
                resource.setName(name);
                resource.setKind("POOL");
                resource.setResourceType(roomType);
                resource.setStatus("ACTIVE");
                resource.setPoolTotalUnits(units);
                resource.setOperaRoomId(code.getCode());
                resource = resourceRepo.save(resource);
                updated++;
            }
            String localRef = resource.getId().toString();
            OperaCodeMap map = mapRepo.findByTenantIdAndOperaHotelIdAndKindAndLocalRef(
                            hotel.getTenantId(), hotel.getId(), OperaCodeMap.Kind.ROOM_TYPE, localRef)
                    .or(() -> mapRepo.findByTenantIdAndOperaHotelIdAndKindAndOperaCode(
                            hotel.getTenantId(), hotel.getId(), OperaCodeMap.Kind.ROOM_TYPE, code.getCode()))
                    .orElseGet(() -> OperaCodeMap.builder()
                            .tenantId(hotel.getTenantId())
                            .operaHotelId(hotel.getId())
                            .kind(OperaCodeMap.Kind.ROOM_TYPE)
                            .build());
            map.setLocalRef(localRef);
            map.setOperaCode(code.getCode());
            mapRepo.save(map);
            mapped++;
        }
        return new SyncResult(created, updated, mapped, skipped);
    }

    private static boolean skip(OperaCode code) {
        JsonNode attrs = code.getAttrs();
        if (attrs == null || attrs.isNull()) {
            return false;
        }
        return truthy(attrs.get("pseudo")) || truthy(attrs.get("meetingRoom"));
    }

    private static boolean truthy(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        String text = node.asText("").trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "y".equals(text) || "1".equals(text);
    }

    private static int unitsOf(OperaCode code) {
        JsonNode attrs = code.getAttrs();
        if (attrs == null || attrs.isNull() || attrs.get("numberOfRooms") == null || attrs.get("numberOfRooms").isNull()) {
            return 0;
        }
        JsonNode n = attrs.get("numberOfRooms");
        if (n.isNumber()) {
            return Math.max(0, n.intValue());
        }
        try {
            return Math.max(0, Integer.parseInt(n.asText("0").trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
