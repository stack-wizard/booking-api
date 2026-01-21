package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.repository.ResourceRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ResourceService {
    private final ResourceRepository repo;

    public ResourceService(ResourceRepository repo) { this.repo = repo; }

    public List<Resource> findAll() { return repo.findAll(); }
    public Optional<Resource> findById(Long id) { return repo.findById(id); }
    public Resource save(Resource r) { return repo.save(r); }
    public void deleteById(Long id) { repo.deleteById(id); }
}
