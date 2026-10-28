package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.CatalogDtos;
import com.stackwizard.booking_api.model.CrmLookup;
import com.stackwizard.booking_api.model.SalesCatalog;
import com.stackwizard.booking_api.repository.CrmLookupRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Event types a catalog offers on the website: the catalog's codes resolved against the chain's EVENT_TYPE code
 * list. Codes deactivated or removed from the code list drop out without touching the catalog.
 */
@Service
public class CatalogEventTypeService {
    private final CrmLookupRepository lookupRepo;

    public CatalogEventTypeService(CrmLookupRepository lookupRepo) {
        this.lookupRepo = lookupRepo;
    }

    /** Active event types of the catalog, in the catalog's order. Reads only, so it is safe in read-only transactions. */
    public List<CatalogDtos.EventTypeView> options(SalesCatalog catalog) {
        if (catalog.getEventTypes() == null || catalog.getEventTypes().isEmpty()) {
            return List.of();
        }
        Map<String, CrmLookup> active = new HashMap<>();
        for (CrmLookup entry : lookupRepo.findByTenantIdAndKindOrderByDisplayOrderAscIdAsc(catalog.getTenantId(), CrmLookup.Kind.EVENT_TYPE)) {
            if (Boolean.TRUE.equals(entry.getActive())) {
                active.put(entry.getCode().toUpperCase(Locale.ROOT), entry);
            }
        }
        return catalog.getEventTypes().stream()
                .map(code -> active.get(code.toUpperCase(Locale.ROOT)))
                .filter(java.util.Objects::nonNull)
                .map(e -> new CatalogDtos.EventTypeView(e.getCode(), e.getLabel()))
                .toList();
    }

    /**
     * The event type of a website inquiry. When the catalog offers event types, one of them is required. A catalog
     * without event types takes none (a value sent anyway is ignored, so an older website keeps working).
     */
    public Optional<CatalogDtos.EventTypeView> requireForInquiry(SalesCatalog catalog, String value) {
        List<CatalogDtos.EventTypeView> options = options(catalog);
        if (options.isEmpty()) {
            return Optional.empty();
        }
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("eventType is required; one of " + codes(options));
        }
        String code = value.trim();
        return Optional.of(options.stream()
                .filter(o -> o.code().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("eventType " + code + " is not offered by catalog "
                        + catalog.getCode() + "; one of " + codes(options))));
    }

    private static String codes(List<CatalogDtos.EventTypeView> options) {
        return String.join(", ", options.stream().map(CatalogDtos.EventTypeView::code).toList());
    }
}
