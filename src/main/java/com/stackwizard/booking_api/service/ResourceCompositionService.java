package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.ResourceComposition;
import com.stackwizard.booking_api.repository.ResourceCompositionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ResourceCompositionService {
    private final ResourceCompositionRepository repo;

    public ResourceCompositionService(ResourceCompositionRepository repo) { this.repo = repo; }

    public List<ResourceComposition> findAll() { return repo.findAll(); }
    public Optional<ResourceComposition> findById(Long id) { return repo.findById(id); }
    public ResourceComposition save(ResourceComposition rc) { return repo.save(rc); }
    public void deleteById(Long id) { repo.deleteById(id); }
}
