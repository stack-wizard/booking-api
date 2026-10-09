package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.PriceProfile;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PriceListEntryResolver {
    private final PriceListEntryRepository priceListRepo;
    private final TenantHierarchy tenantHierarchy;

    public PriceListEntryResolver(PriceListEntryRepository priceListRepo, TenantHierarchy tenantHierarchy) {
        this.priceListRepo = priceListRepo;
        this.tenantHierarchy = tenantHierarchy;
    }

    @Transactional(readOnly = true)
    public List<PriceListEntry> findEffectiveForProductsOnDate(List<Long> productIds,
                                                               Long tenantId,
                                                               LocalDate date,
                                                               ReservationRequest.Type requestType) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        List<PriceListEntry> candidates = priceListRepo.findCandidatesForProductsOnDate(
                productIds,
                tenantHierarchy.orgIdOf(tenantId),
                tenantId,
                date,
                normalizeRequestType(requestType)
        );
        return mergeCandidates(candidates, normalizeRequestType(requestType));
    }

    @Transactional(readOnly = true)
    public List<PriceListEntry> findEffectiveForProductUomOnDate(Long productId,
                                                                 String uom,
                                                                 String currency,
                                                                 Long tenantId,
                                                                 LocalDate date,
                                                                 ReservationRequest.Type requestType) {
        List<PriceListEntry> candidates = priceListRepo.findCandidatesForProductUomOnDate(
                productId,
                uom,
                currency,
                tenantHierarchy.orgIdOf(tenantId),
                tenantId,
                date,
                normalizeRequestType(requestType)
        );
        return mergeCandidates(candidates, normalizeRequestType(requestType));
    }

    private ReservationRequest.Type normalizeRequestType(ReservationRequest.Type requestType) {
        return requestType != null ? requestType : ReservationRequest.Type.EXTERNAL;
    }

    private List<PriceListEntry> mergeCandidates(List<PriceListEntry> candidates,
                                                 ReservationRequest.Type requestType) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        Map<EntryKey, PriceListEntry> effective = new LinkedHashMap<>();
        for (PriceListEntry candidate : candidates) {
            EntryKey key = EntryKey.from(candidate);
            PriceListEntry current = effective.get(key);
            if (current == null || isMoreSpecific(candidate, current, requestType)) {
                effective.put(key, candidate);
            }
        }
        return new ArrayList<>(effective.values());
    }

    private boolean isMoreSpecific(PriceListEntry candidate,
                                   PriceListEntry current,
                                   ReservationRequest.Type requestType) {
        return specificity(candidate, requestType) < specificity(current, requestType);
    }

    private int specificity(PriceListEntry entry, ReservationRequest.Type requestType) {
        PriceProfile profile = entry != null ? entry.getPriceProfile() : null;
        // A hotel's own price beats the chain price; within the same level the matching request type wins.
        int level = profile != null && profile.getPropertyTenantId() != null ? 0 : 2;
        int type = profile != null && profile.getReservationRequestType() == requestType ? 0 : 1;
        return level + type;
    }

    private record EntryKey(Long productId,
                            String uom,
                            String currency,
                            LocalTime startTime,
                            LocalTime endTime) {
        private static EntryKey from(PriceListEntry entry) {
            PriceProfile profile = entry.getPriceProfile();
            return new EntryKey(
                    entry.getProductId(),
                    normalize(entry.getUom()),
                    normalize(profile != null ? profile.getCurrency() : null),
                    entry.getStartTime(),
                    entry.getEndTime()
            );
        }

        private static String normalize(String value) {
            return value == null ? null : value.trim().toUpperCase();
        }
    }
}
