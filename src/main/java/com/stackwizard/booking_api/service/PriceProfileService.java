package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.PriceProfile;
import com.stackwizard.booking_api.repository.PriceProfileRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PriceProfileService {
    private final PriceProfileRepository repo;

    public PriceProfileService(PriceProfileRepository repo) { this.repo = repo; }

    public List<PriceProfile> findAll() { return repo.findAll(); }
    public Optional<PriceProfile> findById(Long id) { return repo.findById(id); }
    public PriceProfile save(PriceProfile profile) { return repo.save(profile); }
    public void deleteById(Long id) { repo.deleteById(id); }
}
