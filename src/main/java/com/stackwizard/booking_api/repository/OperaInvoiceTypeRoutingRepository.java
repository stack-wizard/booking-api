package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.model.OperaInvoiceTypeRouting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OperaInvoiceTypeRoutingRepository extends JpaRepository<OperaInvoiceTypeRouting, Long> {
    List<OperaInvoiceTypeRouting> findByTenantIdOrderByInvoiceTypeAscHotelCodeAscIdAsc(Long tenantId);

    List<OperaInvoiceTypeRouting> findByTenantIdAndInvoiceTypeAndActiveTrueOrderByHotelCodeAscIdAsc(Long tenantId,
                                                                                                      InvoiceType invoiceType);

    Optional<OperaInvoiceTypeRouting> findByTenantIdAndInvoiceTypeAndHotelCodeIgnoreCaseAndActiveTrue(Long tenantId,
                                                                                                        InvoiceType invoiceType,
                                                                                                        String hotelCode);
}
