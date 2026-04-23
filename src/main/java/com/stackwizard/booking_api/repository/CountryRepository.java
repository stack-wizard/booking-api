package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Country;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CountryRepository extends JpaRepository<Country, String> {
}
