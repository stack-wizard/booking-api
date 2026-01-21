package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.LocationNode;
import com.stackwizard.booking_api.repository.LocationNodeRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class LocationNodeService {
    private final LocationNodeRepository repo;

    public LocationNodeService(LocationNodeRepository repo) { this.repo = repo; }

    public List<LocationNode> findAll() { return repo.findAll(); }
    public Optional<LocationNode> findById(Long id) { return repo.findById(id); }
    public LocationNode save(LocationNode n) { return repo.save(n); }
    public void deleteById(Long id) { repo.deleteById(id); }
}
