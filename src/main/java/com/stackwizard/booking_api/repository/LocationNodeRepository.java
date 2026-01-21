package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LocationNode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationNodeRepository extends JpaRepository<LocationNode, Long> {
}
