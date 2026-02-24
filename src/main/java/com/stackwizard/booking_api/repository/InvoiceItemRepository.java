package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.InvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, Long> {
    List<InvoiceItem> findByInvoiceIdOrderByLineNoAsc(Long invoiceId);
}
