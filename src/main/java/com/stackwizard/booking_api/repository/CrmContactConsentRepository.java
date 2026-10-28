package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmContactConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmContactConsentRepository extends JpaRepository<CrmContactConsent, Long> {
    List<CrmContactConsent> findByContactIdOrderByChannelAsc(Long contactId);

    Optional<CrmContactConsent> findByContactIdAndChannel(Long contactId, CrmContactConsent.Channel channel);
}
