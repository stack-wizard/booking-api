package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PriceListEntryService {
    private final PriceListEntryRepository repo;

    public PriceListEntryService(PriceListEntryRepository repo) { this.repo = repo; }

    public List<PriceListEntry> findAll() { return repo.findAll(); }
    public Optional<PriceListEntry> findById(Long id) { return repo.findById(id); }
    public PriceListEntry save(PriceListEntry entry) { return repo.save(entry); }
    public void deleteById(Long id) { repo.deleteById(id); }
}
