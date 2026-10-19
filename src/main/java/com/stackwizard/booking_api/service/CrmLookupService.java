package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CrmLookup;
import com.stackwizard.booking_api.repository.CrmLookupRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Chain code lists (event types, setup styles, event preferences, additional services).
 * Codes are stored on records, so a code never changes once created; entries are deactivated, not deleted.
 */
@Service
public class CrmLookupService {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,40}");

    static final Map<CrmLookup.Kind, List<String[]>> DEFAULTS = Map.of(
            CrmLookup.Kind.EVENT_TYPE, List.of(
                    new String[]{"MEETING", "Meeting"},
                    new String[]{"CONFERENCE", "Conference"},
                    new String[]{"TRAINING", "Training"},
                    new String[]{"INCENTIVE", "Incentive"},
                    new String[]{"WEDDING", "Wedding"},
                    new String[]{"GALA_DINNER", "Gala dinner"},
                    new String[]{"PARTY", "Party"},
                    new String[]{"OTHER", "Other"}),
            CrmLookup.Kind.SETUP_STYLE, List.of(
                    new String[]{"THEATRE", "Theatre"},
                    new String[]{"CLASSROOM", "Classroom"},
                    new String[]{"U_SHAPE", "U-shape"},
                    new String[]{"BOARDROOM", "Boardroom"},
                    new String[]{"BANQUET", "Banquet"},
                    new String[]{"CABARET", "Cabaret"},
                    new String[]{"RECEPTION", "Reception"},
                    new String[]{"HOLLOW_SQUARE", "Hollow square"}),
            CrmLookup.Kind.EVENT_PREFERENCE, List.of(
                    new String[]{"WELCOME_COFFEE", "Welcome coffee"},
                    new String[]{"COFFEE_BREAKS", "Coffee breaks"},
                    new String[]{"LUNCH", "Lunch"},
                    new String[]{"DINNER", "Dinner"},
                    new String[]{"COCKTAIL", "Cocktail reception"},
                    new String[]{"GALA_DINNER", "Gala dinner"}),
            CrmLookup.Kind.ADDITIONAL_SERVICE, List.of(
                    new String[]{"AV", "Audiovisual equipment"},
                    new String[]{"TECH_SUPPORT", "Technical support"},
                    new String[]{"STAGE", "Stage"},
                    new String[]{"DECORATION", "Decoration"},
                    new String[]{"ACCOMMODATION", "Accommodation block"},
                    new String[]{"TRANSFER", "Transfers"}));

    private final CrmLookupRepository repo;
    private final JdbcTemplate jdbc;
    private final CrmAccessContext accessContext;

    public CrmLookupService(CrmLookupRepository repo, JdbcTemplate jdbc, CrmAccessContext accessContext) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.accessContext = accessContext;
    }

    @Transactional
    public List<CrmLookup> list(CrmLookup.Kind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        return entries(TenantResolver.requireOrgTenantId(), kind);
    }

    @Transactional
    public CrmLookup create(CrmLookup entry) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        if (entry == null || entry.getKind() == null) {
            throw new IllegalArgumentException("kind is required");
        }
        Long tenantId = TenantResolver.requireOrgTenantId();
        String code = normalizeNewCode(entry.getCode());
        List<CrmLookup> existing = entries(tenantId, entry.getKind());
        if (existing.stream().anyMatch(e -> e.getCode().equalsIgnoreCase(code))) {
            throw new IllegalArgumentException("Code " + code + " already exists");
        }
        return repo.save(CrmLookup.builder()
                .tenantId(tenantId)
                .kind(entry.getKind())
                .code(code)
                .label(requireLabel(entry.getLabel()))
                .displayOrder(entry.getDisplayOrder() != null ? entry.getDisplayOrder()
                        : existing.stream().mapToInt(CrmLookup::getDisplayOrder).max().orElse(0) + 10)
                .active(entry.getActive() == null || entry.getActive())
                .build());
    }

    @Transactional
    public CrmLookup update(Long id, CrmLookup changes) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        CrmLookup existing = repo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Code list entry not found: " + id));
        if (changes == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (changes.getCode() != null && !changes.getCode().trim().equalsIgnoreCase(existing.getCode())) {
            throw new IllegalArgumentException("Code cannot be changed; add a new entry and deactivate this one");
        }
        if (changes.getLabel() != null) {
            existing.setLabel(requireLabel(changes.getLabel()));
        }
        if (changes.getDisplayOrder() != null) {
            existing.setDisplayOrder(changes.getDisplayOrder());
        }
        if (changes.getActive() != null) {
            existing.setActive(changes.getActive());
        }
        return repo.save(existing);
    }

    /**
     * Canonical code for a value saved on a record. Blank is null. A value equal to {@code previous} is kept
     * even when it is no longer active, so old records stay editable.
     */
    @Transactional
    public String requireCode(Long orgTenantId, CrmLookup.Kind kind, String value, String previous) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (previous != null && trimmed.equalsIgnoreCase(previous.trim())) {
            return previous;
        }
        return entries(orgTenantId, kind).stream()
                .filter(e -> Boolean.TRUE.equals(e.getActive()))
                .filter(e -> e.getCode().equalsIgnoreCase(trimmed))
                .map(CrmLookup::getCode)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown " + kind.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ": " + trimmed));
    }

    /**
     * Makes the chain's list of a kind equal to OPERA's: every OPERA code is an active entry (label from OPERA),
     * every other entry is deactivated. Returns the number of active OPERA entries.
     */
    @Transactional
    public int alignWithOpera(Long orgTenantId, CrmLookup.Kind kind, Map<String, String> labelsByCode) {
        List<CrmLookup> existing = entries(orgTenantId, kind);
        int order = existing.stream().mapToInt(CrmLookup::getDisplayOrder).max().orElse(0);
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (Map.Entry<String, String> entry : labelsByCode.entrySet()) {
            String code = entry.getKey().trim().toUpperCase(Locale.ROOT);
            if (!CODE.matcher(code).matches()) {
                continue;
            }
            wanted.add(code);
            String label = StringUtils.hasText(entry.getValue()) ? entry.getValue().trim() : code;
            CrmLookup row = existing.stream().filter(e -> e.getCode().equalsIgnoreCase(code)).findFirst().orElse(null);
            if (row == null) {
                order += 10;
                repo.save(CrmLookup.builder().tenantId(orgTenantId).kind(kind).code(code).label(label)
                        .displayOrder(order).active(true).build());
            } else {
                row.setLabel(label);
                row.setActive(true);
                repo.save(row);
            }
        }
        for (CrmLookup row : existing) {
            if (!wanted.contains(row.getCode().toUpperCase(Locale.ROOT)) && Boolean.TRUE.equals(row.getActive())) {
                row.setActive(false);
                repo.save(row);
            }
        }
        return wanted.size();
    }

    private List<CrmLookup> entries(Long tenantId, CrmLookup.Kind kind) {
        if (!repo.existsByTenantIdAndKind(tenantId, kind)) {
            int order = 0;
            for (String[] row : DEFAULTS.getOrDefault(kind, List.of())) {
                order += 10;
                jdbc.update("""
                        insert into crm_lookup (tenant_id, kind, code, label, display_order, active)
                        values (?, ?, ?, ?, ?, true)
                        on conflict (tenant_id, kind, upper(code)) do nothing
                        """, tenantId, kind.name(), row[0], row[1], order);
            }
        }
        return repo.findByTenantIdAndKindOrderByDisplayOrderAscIdAsc(tenantId, kind);
    }

    private static String normalizeNewCode(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("code is required");
        }
        String code = value.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("code may contain only letters, digits and _ (max 40)");
        }
        return code;
    }

    private static String requireLabel(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("label is required");
        }
        return value.trim();
    }
}
