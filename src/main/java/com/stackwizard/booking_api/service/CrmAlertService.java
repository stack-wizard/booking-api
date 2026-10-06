package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CrmAlert;
import com.stackwizard.booking_api.repository.CrmAlertRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmOwnerScope;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class CrmAlertService {
    private final CrmAlertRepository alertRepo;
    private final CrmAccessContext accessContext;

    public CrmAlertService(CrmAlertRepository alertRepo, CrmAccessContext accessContext) {
        this.alertRepo = alertRepo;
        this.accessContext = accessContext;
    }

    /** Open alerts for me (and my team for TEAM scope, everyone for ALL scope), plus unassigned ones. */
    @Transactional(readOnly = true)
    public List<CrmAlert> openForMe() {
        accessContext.require(CrmPermission.EVENT_READ);
        CrmOwnerScope scope = CrmOwnerScope.from(accessContext);
        Set<Long> userIds = new HashSet<>();
        userIds.add(-1L);
        if (scope.currentUserId() != null) {
            userIds.add(scope.currentUserId());
        }
        if (scope.team() && scope.teamUserIds() != null) {
            userIds.addAll(scope.teamUserIds());
        }
        return alertRepo.findOpen(TenantResolver.requireTenantId(), scope.all(), userIds);
    }

    @Transactional
    public CrmAlert acknowledge(Long alertId) {
        accessContext.require(CrmPermission.EVENT_READ);
        CrmAlert alert = alertRepo.findByIdAndTenantId(alertId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));
        if (alert.getAcknowledgedAt() == null) {
            alert.setAcknowledgedAt(OffsetDateTime.now());
            alert.setAcknowledgedBy(accessContext.currentUserId());
            alert = alertRepo.save(alert);
        }
        return alert;
    }

    /**
     * Raises the alert once per dedupe key. Runs in its own transaction so a duplicate never rolls back the caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean raise(CrmAlert alert) {
        if (alertRepo.existsByTenantIdAndDedupeKey(alert.getTenantId(), alert.getDedupeKey())) {
            return false;
        }
        try {
            alertRepo.saveAndFlush(alert);
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
    }
}
