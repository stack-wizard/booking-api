package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Service
public class ResourceService {
    private final ResourceRepository repo;

    public ResourceService(ResourceRepository repo) { this.repo = repo; }

    public List<Resource> findAll() { return repo.findByTenantId(TenantResolver.requireTenantId()); }

    public Optional<Resource> findById(Long id) { return repo.findByIdAndTenantId(id, TenantResolver.requireTenantId()); }

    @Transactional
    public Resource save(Resource r) {
        Long tenantId = TenantResolver.requireTenantId();
        if (r.getId() != null && repo.findByIdAndTenantId(r.getId(), tenantId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        }
        r.setTenantId(tenantId);
        return repo.save(r);
    }

    @Transactional
    public void deleteById(Long id) {
        Resource resource = findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found"));
        repo.delete(resource);
    }
}
