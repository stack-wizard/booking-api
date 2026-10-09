package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.TenantOrganizationDto;
import com.stackwizard.booking_api.dto.TenantPropertyDto;
import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.security.PlatformJwtClaims;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TenantPropertyService {
    private final PlatformTenantMappingRepository mappingRepo;

    public TenantPropertyService(PlatformTenantMappingRepository mappingRepo) {
        this.mappingRepo = mappingRepo;
    }

    /**
     * Hotels of the current organization the user may work in. Membership on the organization grants all of
     * them; membership on a single hotel grants only that hotel. A chain without child hotels returns itself.
     */
    @Transactional(readOnly = true)
    public List<TenantPropertyDto> listAccessible(Jwt jwt) {
        Long orgTenantId = TenantResolver.requireOrgTenantId();
        PlatformTenantMapping org = mappingRepo.findByTenantId(orgTenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization is not mapped"));

        List<PlatformTenantMapping> children = mappingRepo
                .findByParentTenantIdOrderByHotelCodeAscTenantIdAsc(orgTenantId);
        if (children.isEmpty()) {
            return List.of(toDto(org));
        }

        boolean all = jwt == null
                || PlatformJwtClaims.isPlatformAdmin(jwt)
                || allowed(jwt).contains(org.getPlatformTenantId().toString().toLowerCase(Locale.ROOT));
        Set<String> allow = jwt == null ? Set.of() : allowed(jwt);
        return children.stream()
                .filter(c -> all || allow.contains(c.getPlatformTenantId().toString().toLowerCase(Locale.ROOT)))
                .map(TenantPropertyService::toDto)
                .toList();
    }

    /**
     * Organizations the caller belongs to. A membership on a hotel resolves to the organization of that hotel,
     * so the chain shows up once. Unmapped memberships are listed as not onboarded.
     */
    @Transactional(readOnly = true)
    public List<TenantOrganizationDto> listOrganizations(Jwt jwt) {
        Map<String, TenantOrganizationDto> out = new LinkedHashMap<>();
        if (jwt != null && PlatformJwtClaims.isPlatformAdmin(jwt)) {
            mappingRepo.findAll().stream()
                    .filter(m -> m.getKind() == PlatformTenantMapping.Kind.ORG)
                    .forEach(m -> out.putIfAbsent(m.getPlatformTenantId().toString(), toOrganization(m)));
            return List.copyOf(out.values());
        }
        for (String raw : jwt == null ? List.<String>of() : PlatformJwtClaims.tenantAllowList(jwt)) {
            UUID platformId;
            try {
                platformId = UUID.fromString(raw.trim());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            Optional<PlatformTenantMapping> mapping = mappingRepo.findByPlatformTenantId(platformId);
            if (mapping.isEmpty()) {
                out.putIfAbsent(platformId.toString(),
                        new TenantOrganizationDto(platformId, null, null, false, List.of()));
                continue;
            }
            PlatformTenantMapping m = mapping.get();
            PlatformTenantMapping org = m.getKind() == PlatformTenantMapping.Kind.PROPERTY && m.getParentTenantId() != null
                    ? mappingRepo.findByTenantId(m.getParentTenantId()).orElse(m)
                    : m;
            out.putIfAbsent(org.getPlatformTenantId().toString(), toOrganization(org));
        }
        return List.copyOf(out.values());
    }

    private TenantOrganizationDto toOrganization(PlatformTenantMapping org) {
        List<UUID> hotels = mappingRepo.findByParentTenantIdOrderByHotelCodeAscTenantIdAsc(org.getTenantId()).stream()
                .map(PlatformTenantMapping::getPlatformTenantId)
                .toList();
        return new TenantOrganizationDto(org.getPlatformTenantId(), org.getTenantId(), org.getName(),
                org.getStatus() != null && org.getStatus() != PlatformTenantMapping.Status.SETUP, hotels);
    }

    private static Set<String> allowed(Jwt jwt) {
        return PlatformJwtClaims.tenantAllowList(jwt).stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private static TenantPropertyDto toDto(PlatformTenantMapping m) {
        return new TenantPropertyDto(m.getTenantId(), m.getPlatformTenantId(), m.getName(), m.getHotelCode(),
                m.getTimezone(), m.getStatus().name());
    }
}
