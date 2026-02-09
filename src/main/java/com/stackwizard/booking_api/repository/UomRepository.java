package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Uom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UomRepository extends JpaRepository<Uom, String> {
    List<Uom> findByActiveTrue();
}
