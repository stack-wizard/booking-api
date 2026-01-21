package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.repository.AllocationRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AllocationService {
    private final AllocationRepository repo;

    public AllocationService(AllocationRepository repo) { this.repo = repo; }

    public List<Allocation> findAll() { return repo.findAll(); }
    public Optional<Allocation> findById(Long id) { return repo.findById(id); }
    public Allocation save(Allocation a) { return repo.save(a); }
    public void deleteById(Long id) { repo.deleteById(id); }
}
