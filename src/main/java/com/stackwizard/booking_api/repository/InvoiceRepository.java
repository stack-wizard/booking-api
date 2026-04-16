package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, Long>, JpaSpecificationExecutor<Invoice> {
    List<Invoice> findByReferenceTableAndReferenceIdOrderByIdAsc(String referenceTable, Long referenceId);

    List<Invoice> findByReservationRequestIdOrderByCreatedAtDescIdDesc(Long reservationRequestId);
}
