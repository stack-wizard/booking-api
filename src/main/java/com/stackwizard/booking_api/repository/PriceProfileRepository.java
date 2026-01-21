package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.PriceProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceProfileRepository extends JpaRepository<PriceProfile, Long> {
}
