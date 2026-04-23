package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.Country;
import com.stackwizard.booking_api.repository.CountryRepository;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/countries")
public class CountryController {
    private final CountryRepository countryRepository;

    public CountryController(CountryRepository countryRepository) {
        this.countryRepository = countryRepository;
    }

    @GetMapping
    public List<Country> list() {
        return countryRepository.findAll(Sort.by(Sort.Order.asc("name")));
    }
}
