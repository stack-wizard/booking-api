package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.ResourceType;
import com.stackwizard.booking_api.repository.ResourceTypeRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ResourceTypeService {
    private final ResourceTypeRepository repo;

    public ResourceTypeService(ResourceTypeRepository repo) {
        this.repo = repo;
    }

    public List<ResourceType> findAll() { return repo.findAll(); }
    public Optional<ResourceType> findById(Long id) { return repo.findById(id); }
    public ResourceType save(ResourceType t) { return repo.save(t); }
    public void deleteById(Long id) { repo.deleteById(id); }
}
