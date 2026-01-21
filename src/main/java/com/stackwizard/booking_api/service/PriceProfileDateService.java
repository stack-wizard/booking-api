package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.PriceProfileDate;
import com.stackwizard.booking_api.repository.PriceProfileDateRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PriceProfileDateService {
    private final PriceProfileDateRepository repo;

    public PriceProfileDateService(PriceProfileDateRepository repo) { this.repo = repo; }

    public List<PriceProfileDate> findAll() { return repo.findAll(); }
    public Optional<PriceProfileDate> findById(Long id) { return repo.findById(id); }
    public PriceProfileDate save(PriceProfileDate profileDate) { return repo.save(profileDate); }
    public void deleteById(Long id) { repo.deleteById(id); }
}
