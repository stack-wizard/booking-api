package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.InvoiceSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InvoiceSequenceRepository extends JpaRepository<InvoiceSequence, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from InvoiceSequence s
            where s.tenantId = :tenantId
              and s.invoiceType = :invoiceType
              and s.invoiceYear = :invoiceYear
            """)
    Optional<InvoiceSequence> findForUpdate(@Param("tenantId") Long tenantId,
                                            @Param("invoiceType") String invoiceType,
                                            @Param("invoiceYear") Integer invoiceYear);
}
