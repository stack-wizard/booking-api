package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceComposition;
import com.stackwizard.booking_api.repository.ResourceCompositionRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Service
public class ResourceCompositionService {
    private final ResourceCompositionRepository repo;
    private final ResourceRepository resourceRepo;

    public ResourceCompositionService(ResourceCompositionRepository repo, ResourceRepository resourceRepo) {
        this.repo = repo;
        this.resourceRepo = resourceRepo;
    }

    public List<ResourceComposition> findAll() { return repo.findByTenantId(TenantResolver.requireTenantId()); }

    public Optional<ResourceComposition> findById(Long id) { return repo.findByIdAndTenantId(id, TenantResolver.requireTenantId()); }

    @Transactional
    public ResourceComposition save(ResourceComposition rc) {
        Long tenantId = TenantResolver.requireTenantId();
        if (rc.getId() != null && repo.findByIdAndTenantId(rc.getId(), tenantId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Composition not found");
        }
        Resource parent = requireResource(rc.getParentResource(), tenantId, "parentResource");
        Resource member = requireResource(rc.getMemberResource(), tenantId, "memberResource");
        if (parent.getId().equals(member.getId())) {
            throw new IllegalArgumentException("A resource cannot contain itself");
        }
        if (rc.getQty() == null || rc.getQty() < 1) {
            throw new IllegalArgumentException("qty must be at least 1");
        }
        rc.setTenantId(tenantId);
        rc.setParentResource(parent);
        rc.setMemberResource(member);
        return repo.save(rc);
    }

    @Transactional
    public void deleteById(Long id) {
        ResourceComposition rc = findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Composition not found"));
        repo.delete(rc);
    }

    private Resource requireResource(Resource ref, Long tenantId, String field) {
        if (ref == null || ref.getId() == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return resourceRepo.findByIdAndTenantId(ref.getId(), tenantId)
                .orElseThrow(() -> new IllegalArgumentException(field + " not found"));
    }
}
