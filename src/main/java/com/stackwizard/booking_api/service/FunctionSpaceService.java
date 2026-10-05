package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.model.FunctionSpace;
import com.stackwizard.booking_api.model.FunctionSpaceSetup;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.repository.FunctionSpaceRepository;
import com.stackwizard.booking_api.repository.FunctionSpaceSetupRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class FunctionSpaceService {
    private static final Set<String> ALLOWED_RESOURCE_TYPES = Set.of("MEETING_ROOM", "MEETING_OFFICE");

    private final FunctionSpaceRepository spaceRepo;
    private final FunctionSpaceSetupRepository setupRepo;
    private final ResourceRepository resourceRepo;
    private final CrmAccessContext accessContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FunctionSpaceService(FunctionSpaceRepository spaceRepo,
                                FunctionSpaceSetupRepository setupRepo,
                                ResourceRepository resourceRepo,
                                CrmAccessContext accessContext) {
        this.spaceRepo = spaceRepo;
        this.setupRepo = setupRepo;
        this.resourceRepo = resourceRepo;
        this.accessContext = accessContext;
    }

    public List<FunctionSpace> findAll(Boolean activeOnly) {
        Long tenantId = TenantResolver.requireTenantId();
        if (Boolean.TRUE.equals(activeOnly)) {
            return spaceRepo.findByTenantIdAndActiveTrueOrderByIdAsc(tenantId);
        }
        return spaceRepo.findByTenantIdOrderByIdAsc(tenantId);
    }

    public Optional<FunctionSpace> findById(Long id) {
        return spaceRepo.findByIdAndTenantId(id, TenantResolver.requireTenantId());
    }

    @Transactional
    public FunctionSpace create(FunctionSpace space) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        Long tenantId = TenantResolver.requireTenantId();
        if (space.getResourceId() == null) {
            throw new IllegalArgumentException("resourceId is required");
        }
        Resource resource = requireMeetingResource(tenantId, space.getResourceId());
        spaceRepo.findByTenantIdAndResourceId(tenantId, resource.getId()).ifPresent(existing -> {
            throw new IllegalStateException("Function space already exists for resource " + resource.getId());
        });

        space.setId(null);
        space.setTenantId(tenantId);
        space.setResourceId(resource.getId());
        if (space.getNaturalLight() == null) {
            space.setNaturalLight(false);
        }
        if (space.getDivisible() == null) {
            space.setDivisible(false);
        }
        if (space.getMinDurationMinutes() == null) {
            space.setMinDurationMinutes(60);
        }
        if (space.getActive() == null) {
            space.setActive(true);
        }
        if (space.getAttrs() == null) {
            space.setAttrs(objectMapper.createObjectNode());
        }
        return spaceRepo.save(space);
    }

    @Transactional
    public FunctionSpace update(Long id, FunctionSpace changes) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        FunctionSpace existing = spaceRepo.findByIdAndTenantId(id, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Function space not found: " + id));
        if (changes.getResourceId() != null && !changes.getResourceId().equals(existing.getResourceId())) {
            throw new IllegalArgumentException("resourceId cannot be changed");
        }
        existing.setAreaSqm(changes.getAreaSqm());
        existing.setFloor(changes.getFloor());
        if (changes.getNaturalLight() != null) {
            existing.setNaturalLight(changes.getNaturalLight());
        }
        if (changes.getDivisible() != null) {
            existing.setDivisible(changes.getDivisible());
        }
        if (changes.getMinDurationMinutes() != null) {
            existing.setMinDurationMinutes(changes.getMinDurationMinutes());
        }
        existing.setDefaultSetupStyle(changes.getDefaultSetupStyle());
        existing.setDescription(changes.getDescription());
        if (changes.getActive() != null) {
            existing.setActive(changes.getActive());
        }
        if (changes.getAttrs() != null) {
            existing.setAttrs(changes.getAttrs());
        }
        return spaceRepo.save(existing);
    }

    @Transactional
    public void softDelete(Long id) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        FunctionSpace existing = spaceRepo.findByIdAndTenantId(id, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Function space not found: " + id));
        existing.setActive(false);
        spaceRepo.save(existing);
    }

    public List<FunctionSpaceSetup> setups(Long functionSpaceId) {
        Long tenantId = TenantResolver.requireTenantId();
        spaceRepo.findByIdAndTenantId(functionSpaceId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Function space not found: " + functionSpaceId));
        return setupRepo.findByTenantIdAndFunctionSpaceIdOrderBySetupStyleAsc(tenantId, functionSpaceId);
    }

    @Transactional
    public FunctionSpaceSetup createSetup(Long functionSpaceId, FunctionSpaceSetup setup) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        Long tenantId = TenantResolver.requireTenantId();
        spaceRepo.findByIdAndTenantId(functionSpaceId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Function space not found: " + functionSpaceId));
        if (setup.getSetupStyle() == null) {
            throw new IllegalArgumentException("setupStyle is required");
        }
        if (setup.getCapacity() == null || setup.getCapacity() <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        setupRepo.findByTenantIdAndFunctionSpaceIdAndSetupStyle(tenantId, functionSpaceId, setup.getSetupStyle())
                .ifPresent(existing -> {
                    throw new IllegalStateException("Setup already exists for style " + setup.getSetupStyle());
                });
        setup.setId(null);
        setup.setTenantId(tenantId);
        setup.setFunctionSpaceId(functionSpaceId);
        return setupRepo.save(setup);
    }

    @Transactional
    public FunctionSpaceSetup updateSetup(Long setupId, FunctionSpaceSetup changes) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        FunctionSpaceSetup existing = setupRepo.findByIdAndTenantId(setupId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Setup not found: " + setupId));
        if (changes.getCapacity() != null) {
            if (changes.getCapacity() <= 0) {
                throw new IllegalArgumentException("capacity must be > 0");
            }
            existing.setCapacity(changes.getCapacity());
        }
        existing.setNotes(changes.getNotes());
        return setupRepo.save(existing);
    }

    @Transactional
    public void deleteSetup(Long setupId) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        FunctionSpaceSetup existing = setupRepo.findByIdAndTenantId(setupId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Setup not found: " + setupId));
        setupRepo.delete(existing);
    }

    /**
     * Candidate spaces that can seat at least {@code minPax} in any setup style.
     */
    public List<FunctionSpaceSetup> findSetupsWithCapacity(Integer minPax) {
        if (minPax == null || minPax <= 0) {
            throw new IllegalArgumentException("minPax must be > 0");
        }
        return setupRepo.findByTenantIdAndCapacityGreaterThanEqualOrderByCapacityAsc(
                TenantResolver.requireTenantId(), minPax);
    }

    private Resource requireMeetingResource(Long tenantId, Long resourceId) {
        Resource resource = resourceRepo.findByIdWithType(resourceId)
                .orElseThrow(() -> new IllegalArgumentException("Resource not found: " + resourceId));
        if (!tenantId.equals(resource.getTenantId())) {
            throw new IllegalArgumentException("Resource does not belong to tenant");
        }
        if (resource.getResourceType() == null || resource.getResourceType().getCode() == null) {
            throw new IllegalArgumentException("Resource has no type");
        }
        String code = resource.getResourceType().getCode().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_RESOURCE_TYPES.contains(code)) {
            throw new IllegalArgumentException(
                    "Resource type must be MEETING_ROOM or MEETING_OFFICE, got " + code);
        }
        return resource;
    }
}
