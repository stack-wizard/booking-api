package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.ResourceMap;
import com.stackwizard.booking_api.repository.ResourceMapRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ResourceMapService {
    private final ResourceMapRepository repo;

    public ResourceMapService(ResourceMapRepository repo) { this.repo = repo; }

    public List<ResourceMap> findAll() { return repo.findAll(); }
    public Optional<ResourceMap> findById(Long id) { return repo.findById(id); }
    public List<ResourceMap> findByTenantId(Long tenantId) { return repo.findByTenantId(tenantId); }
    public ResourceMap save(ResourceMap map) { return repo.save(map); }
    public void deleteById(Long id) { repo.deleteById(id); }
}
