package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.TenantSetupStatusDto;
import com.stackwizard.booking_api.dto.TenantSetupStatusDto.PropertyStatus;
import com.stackwizard.booking_api.dto.TenantSetupStatusDto.Step;
import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.repository.FiscalBusinessPremiseRepository;
import com.stackwizard.booking_api.repository.OperaHotelRepository;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.repository.TenantConfigRepository;
import com.stackwizard.booking_api.repository.TenantIntegrationConfigRepository;
import com.stackwizard.booking_api.service.PlatformDirectoryClient.PlatformChild;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit tenant onboarding: an organization (Platform parent) is registered together with its hotels
 * (Platform children). Hotels start in {@code SETUP} and become {@code ACTIVE} once the required steps are done.
 * An organization must always have at least one hotel.
 */
@Service
public class TenantSetupService {

    public static final String STEP_TENANT_CONFIG = "TENANT_CONFIG";
    public static final String STEP_RESOURCE = "RESOURCE";
    public static final String STEP_OPERA_HOTEL = "OPERA_HOTEL";
    public static final String STEP_FISCAL = "FISCAL";
    public static final String STEP_PAYMENT_PROVIDER = "PAYMENT_PROVIDER";

    private final PlatformTenantMappingRepository mappingRepo;
    private final PlatformTenantResolver tenantResolver;
    private final PlatformDirectoryClient directory;
    private final TenantConfigRepository tenantConfigRepo;
    private final ResourceRepository resourceRepo;
    private final OperaHotelRepository operaHotelRepo;
    private final FiscalBusinessPremiseRepository fiscalPremiseRepo;
    private final TenantIntegrationConfigRepository integrationRepo;
    private final JdbcTemplate jdbc;

    public TenantSetupService(PlatformTenantMappingRepository mappingRepo,
                              PlatformTenantResolver tenantResolver,
                              PlatformDirectoryClient directory,
                              TenantConfigRepository tenantConfigRepo,
                              ResourceRepository resourceRepo,
                              OperaHotelRepository operaHotelRepo,
                              FiscalBusinessPremiseRepository fiscalPremiseRepo,
                              TenantIntegrationConfigRepository integrationRepo,
                              JdbcTemplate jdbc) {
        this.mappingRepo = mappingRepo;
        this.tenantResolver = tenantResolver;
        this.directory = directory;
        this.tenantConfigRepo = tenantConfigRepo;
        this.resourceRepo = resourceRepo;
        this.operaHotelRepo = operaHotelRepo;
        this.fiscalPremiseRepo = fiscalPremiseRepo;
        this.integrationRepo = integrationRepo;
        this.jdbc = jdbc;
    }

    /**
     * Register the organization (if needed) and mirror its Platform hotels. Safe to call again whenever a hotel
     * is added in Platform.
     */
    @Transactional
    public TenantSetupStatusDto registerOrSync(UUID orgPlatformId, String bearerToken) {
        List<PlatformChild> children = directory.listChildren(orgPlatformId, bearerToken).stream()
                .filter(PlatformChild::active)
                .toList();
        if (children.isEmpty()) {
            throw new IllegalStateException(
                    "Organization has no active hotel in Mikos Platform. Create at least one child tenant first.");
        }

        PlatformTenantMapping org = mappingRepo.findByPlatformTenantId(orgPlatformId).orElse(null);
        if (org == null) {
            String name = directory.findTenant(orgPlatformId, bearerToken)
                    .map(PlatformDirectoryClient.PlatformTenantSummary::name)
                    .orElse(null);
            org = mappingRepo.saveAndFlush(PlatformTenantMapping.builder()
                    .tenantId(mappingRepo.nextTenantId())
                    .platformTenantId(orgPlatformId)
                    .kind(PlatformTenantMapping.Kind.ORG)
                    .status(PlatformTenantMapping.Status.ACTIVE)
                    .name(name)
                    .build());
            tenantResolver.ensureTenantDefaults(org.getTenantId());
        } else if (org.getKind() != PlatformTenantMapping.Kind.ORG) {
            throw new IllegalStateException(
                    "Platform tenant is a hotel; register its parent organization instead.");
        }

        for (PlatformChild child : children) {
            syncChild(org, child);
        }
        return status(orgPlatformId);
    }

    private void syncChild(PlatformTenantMapping org, PlatformChild child) {
        Optional<PlatformTenantMapping> existing = mappingRepo.findByPlatformTenantId(child.id());
        if (existing.isEmpty()) {
            PlatformTenantMapping created = mappingRepo.saveAndFlush(PlatformTenantMapping.builder()
                    .tenantId(mappingRepo.nextTenantId())
                    .platformTenantId(child.id())
                    .parentTenantId(org.getTenantId())
                    .kind(PlatformTenantMapping.Kind.PROPERTY)
                    .status(PlatformTenantMapping.Status.SETUP)
                    .name(child.name())
                    .hotelCode(child.hotelCode())
                    .build());
            tenantResolver.ensureTenantDefaults(created.getTenantId());
            return;
        }
        PlatformTenantMapping mapping = existing.get();
        if (mapping.getKind() != PlatformTenantMapping.Kind.PROPERTY
                || !org.getTenantId().equals(mapping.getParentTenantId())) {
            throw new IllegalStateException("Platform tenant " + child.id()
                    + " is already registered under a different organization");
        }
        mapping.setName(child.name());
        mapping.setHotelCode(child.hotelCode());
        mappingRepo.save(mapping);
    }

    @Transactional(readOnly = true)
    public TenantSetupStatusDto status(UUID orgPlatformId) {
        Optional<PlatformTenantMapping> org = mappingRepo.findByPlatformTenantId(orgPlatformId);
        if (org.isEmpty()) {
            return new TenantSetupStatusDto(false, orgPlatformId, null, null, List.of());
        }
        PlatformTenantMapping orgMapping = requireOrg(org.get());
        List<PropertyStatus> properties = mappingRepo
                .findByParentTenantIdOrderByHotelCodeAscTenantIdAsc(orgMapping.getTenantId()).stream()
                .map(this::propertyStatus)
                .toList();
        return new TenantSetupStatusDto(true, orgPlatformId, orgMapping.getTenantId(), orgMapping.getName(),
                properties);
    }

    /** Moves a hotel from {@code SETUP} to {@code ACTIVE} when every required step is done. */
    @Transactional
    public PropertyStatus activate(UUID orgPlatformId, Long propertyTenantId) {
        PlatformTenantMapping org = requireOrg(mappingRepo.findByPlatformTenantId(orgPlatformId)
                .orElseThrow(() -> new IllegalArgumentException("Organization is not registered")));
        PlatformTenantMapping property = mappingRepo.findByTenantId(propertyTenantId)
                .filter(p -> p.getKind() == PlatformTenantMapping.Kind.PROPERTY
                        && org.getTenantId().equals(p.getParentTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Hotel does not belong to this organization"));

        PropertyStatus current = propertyStatus(property);
        if (!current.readyToActivate()) {
            List<String> missing = current.steps().stream()
                    .filter(s -> s.required() && !s.done())
                    .map(Step::label)
                    .toList();
            throw new IllegalStateException("Hotel is not ready to activate. Missing: " + String.join(", ", missing));
        }
        property.setStatus(PlatformTenantMapping.Status.ACTIVE);
        mappingRepo.save(property);
        return propertyStatus(property);
    }

    /**
     * Moves the chain-level data of a hotel (catalog, CRM, events, sales documents, people) to its organization,
     * see {@code TenantLevel.ORG_TABLES}. All or nothing; fails when the organization already owns a row with the
     * same unique code. Idempotent: a second run finds nothing to move.
     */
    @Transactional
    public void promoteCatalogToOrg(UUID orgPlatformId, Long propertyTenantId) {
        PlatformTenantMapping org = requireOrg(mappingRepo.findByPlatformTenantId(orgPlatformId)
                .orElseThrow(() -> new IllegalArgumentException("Organization is not registered")));
        PlatformTenantMapping property = mappingRepo.findByTenantId(propertyTenantId)
                .filter(p -> p.getKind() == PlatformTenantMapping.Kind.PROPERTY
                        && org.getTenantId().equals(p.getParentTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Hotel does not belong to this organization"));
        try {
            jdbc.queryForList("select booking_promote_catalog(?, ?)", property.getTenantId(), org.getTenantId());
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("Chain-level data of hotel " + property.getHotelCode()
                    + " conflicts with data the organization already owns (same code). Nothing was moved.");
        }
    }

    private PropertyStatus propertyStatus(PlatformTenantMapping property) {
        Long tenantId = property.getTenantId();
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(STEP_TENANT_CONFIG, "Tenant configuration", true,
                tenantConfigRepo.findByTenantId(tenantId).isPresent()));
        steps.add(new Step(STEP_RESOURCE, "At least one space or resource", true,
                resourceRepo.existsByTenantId(tenantId)));
        steps.add(new Step(STEP_OPERA_HOTEL, "Opera hotel configuration", false,
                !operaHotelRepo.findByTenantIdOrderByHotelCodeAscIdAsc(tenantId).isEmpty()));
        steps.add(new Step(STEP_FISCAL, "Fiscal premise", false,
                !fiscalPremiseRepo.findByTenantId(tenantId).isEmpty()));
        steps.add(new Step(STEP_PAYMENT_PROVIDER, "Payment provider", false,
                !integrationRepo.findByTenantId(tenantId).isEmpty()));
        boolean ready = steps.stream().filter(Step::required).allMatch(Step::done);
        return new PropertyStatus(tenantId, property.getPlatformTenantId(), property.getName(),
                property.getHotelCode(), property.getStatus().name(), ready, steps);
    }

    private static PlatformTenantMapping requireOrg(PlatformTenantMapping mapping) {
        if (mapping.getKind() != PlatformTenantMapping.Kind.ORG) {
            throw new IllegalArgumentException("Platform tenant is a hotel; use its parent organization");
        }
        return mapping;
    }
}
