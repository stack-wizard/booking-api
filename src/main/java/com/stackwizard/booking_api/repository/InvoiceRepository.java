package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    Optional<Invoice> findByReferenceTableAndReferenceId(String referenceTable, Long referenceId);
}
