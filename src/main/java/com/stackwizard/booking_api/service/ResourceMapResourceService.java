package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.ResourceMapResource;
import com.stackwizard.booking_api.repository.ResourceMapResourceRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ResourceMapResourceService {
    private final ResourceMapResourceRepository repo;

    public ResourceMapResourceService(ResourceMapResourceRepository repo) { this.repo = repo; }

    public List<ResourceMapResource> findAll() { return repo.findAll(); }
    public Optional<ResourceMapResource> findById(Long id) { return repo.findById(id); }
    public List<ResourceMapResource> findByMapId(Long mapId) { return repo.findByResourceMapIdIn(List.of(mapId)); }
    public List<ResourceMapResource> findByResourceId(Long resourceId) { return repo.findByResourceIdIn(List.of(resourceId)); }
    public ResourceMapResource save(ResourceMapResource mapResource) { return repo.save(mapResource); }
    public void deleteById(Long id) { repo.deleteById(id); }
}
