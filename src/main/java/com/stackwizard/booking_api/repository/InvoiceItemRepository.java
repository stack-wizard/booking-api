package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.InvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, Long> {
    List<InvoiceItem> findByInvoiceIdOrderByLineNoAsc(Long invoiceId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from InvoiceItem i where i.invoice.id = :invoiceId")
    int deleteByInvoiceId(@Param("invoiceId") Long invoiceId);
}
