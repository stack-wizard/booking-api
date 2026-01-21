package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.PriceProfileDate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceProfileDateRepository extends JpaRepository<PriceProfileDate, Long> {
}
