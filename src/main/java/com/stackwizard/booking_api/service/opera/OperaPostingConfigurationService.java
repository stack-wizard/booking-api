package com.stackwizard.booking_api.service.opera;

import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.model.OperaInvoiceTypeRouting;
import com.stackwizard.booking_api.repository.OperaHotelRepository;
import com.stackwizard.booking_api.repository.OperaInvoiceTypeRoutingRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Service
public class OperaPostingConfigurationService {
    private final OperaHotelRepository hotelRepo;
    private final OperaInvoiceTypeRoutingRepository routingRepo;

    public OperaPostingConfigurationService(OperaHotelRepository hotelRepo,
                                            OperaInvoiceTypeRoutingRepository routingRepo) {
        this.hotelRepo = hotelRepo;
        this.routingRepo = routingRepo;
    }

    public List<OperaHotel> findHotels(Long tenantId) {
        return tenantId == null ? hotelRepo.findAll() : hotelRepo.findByTenantIdOrderByHotelCodeAscIdAsc(tenantId);
    }

    public OperaHotel saveHotel(OperaHotel hotel) {
        if (hotel == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (hotel.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (!StringUtils.hasText(hotel.getHotelCode())) {
            throw new IllegalArgumentException("hotelCode is required");
        }
        if (hotel.getDefaultCashierId() != null && hotel.getDefaultCashierId() <= 0) {
            throw new IllegalArgumentException("defaultCashierId must be greater than zero");
        }
        if (hotel.getDefaultFolioWindowNo() != null && hotel.getDefaultFolioWindowNo() <= 0) {
            throw new IllegalArgumentException("defaultFolioWindowNo must be greater than zero");
        }
        hotel.setHotelCode(normalizeUpper(hotel.getHotelCode()));
        hotel.setName(normalizeNullable(hotel.getName()));
        if (hotel.getActive() == null) {
            hotel.setActive(Boolean.TRUE);
        }
        return hotelRepo.save(hotel);
    }

    public void deleteHotel(Long id) {
        hotelRepo.deleteById(id);
    }

    public List<OperaInvoiceTypeRouting> findRoutings(Long tenantId) {
        return tenantId == null ? routingRepo.findAll() : routingRepo.findByTenantIdOrderByInvoiceTypeAscHotelCodeAscIdAsc(tenantId);
    }

    public OperaInvoiceTypeRouting saveRouting(OperaInvoiceTypeRouting routing) {
        if (routing == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (routing.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (routing.getInvoiceType() == null) {
            throw new IllegalArgumentException("invoiceType is required");
        }
        if (!StringUtils.hasText(routing.getHotelCode())) {
            throw new IllegalArgumentException("hotelCode is required");
        }
        if (routing.getReservationId() == null || routing.getReservationId() <= 0) {
            throw new IllegalArgumentException("reservationId must be greater than zero");
        }
        String normalizedHotelCode = normalizeUpper(routing.getHotelCode());
        hotelRepo.findByTenantIdAndHotelCodeIgnoreCase(routing.getTenantId(), normalizedHotelCode)
                .orElseThrow(() -> new IllegalArgumentException("hotelCode is not configured for tenant"));
        routing.setHotelCode(normalizedHotelCode);
        if (routing.getActive() == null) {
            routing.setActive(Boolean.TRUE);
        }
        return routingRepo.save(routing);
    }

    public void deleteRouting(Long id) {
        routingRepo.deleteById(id);
    }

    public OperaHotel requireActiveHotel(Long tenantId, String hotelCode) {
        String normalizedHotelCode = normalizeUpper(hotelCode);
        return hotelRepo.findByTenantIdAndHotelCodeIgnoreCaseAndActiveTrue(tenantId, normalizedHotelCode)
                .orElseThrow(() -> new IllegalArgumentException("Active Opera hotel not found for code " + normalizedHotelCode));
    }

    public OperaInvoiceTypeRouting resolveRouting(Long tenantId, InvoiceType invoiceType, String preferredHotelCode) {
        if (tenantId == null || invoiceType == null) {
            throw new IllegalArgumentException("tenantId and invoiceType are required");
        }
        if (StringUtils.hasText(preferredHotelCode)) {
            String normalizedHotelCode = normalizeUpper(preferredHotelCode);
            return routingRepo.findByTenantIdAndInvoiceTypeAndHotelCodeIgnoreCaseAndActiveTrue(
                            tenantId,
                            invoiceType,
                            normalizedHotelCode
                    )
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Opera routing is not configured for invoice type " + invoiceType + " and hotel " + normalizedHotelCode
                    ));
        }
        List<OperaInvoiceTypeRouting> activeRoutings = routingRepo.findByTenantIdAndInvoiceTypeAndActiveTrueOrderByHotelCodeAscIdAsc(
                tenantId,
                invoiceType
        );
        if (activeRoutings.isEmpty()) {
            throw new IllegalArgumentException("Opera routing is not configured for invoice type " + invoiceType);
        }
        if (activeRoutings.size() > 1) {
            throw new IllegalArgumentException("Multiple Opera routings found for invoice type " + invoiceType + "; hotelCode is required");
        }
        return activeRoutings.get(0);
    }

    private String normalizeUpper(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("value is required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
