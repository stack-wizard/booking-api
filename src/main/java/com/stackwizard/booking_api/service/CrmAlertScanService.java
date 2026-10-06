package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CrmAlert;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.SalesContract;
import com.stackwizard.booking_api.model.SalesPaymentMilestone;
import com.stackwizard.booking_api.repository.EventRepository;
import com.stackwizard.booking_api.repository.SalesContractRepository;
import com.stackwizard.booking_api.repository.SalesPaymentMilestoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Daily-style scan that raises one alert per fact (dedupe key includes the date it is about, so moving the
 * date raises a fresh alert). Runs without a request, so it never touches CrmAccessContext.
 */
@Service
public class CrmAlertScanService {
    private static final Logger log = LoggerFactory.getLogger(CrmAlertScanService.class);

    private final EventRepository eventRepo;
    private final SalesPaymentMilestoneRepository milestoneRepo;
    private final SalesContractRepository contractRepo;
    private final CrmAlertService alertService;
    private final int decisionDays;
    private final int guaranteeDays;
    private final int beoDays;
    private final int milestoneDays;

    public CrmAlertScanService(EventRepository eventRepo,
                               SalesPaymentMilestoneRepository milestoneRepo,
                               SalesContractRepository contractRepo,
                               CrmAlertService alertService,
                               @Value("${crm.alerts.decision-days:3}") int decisionDays,
                               @Value("${crm.alerts.guarantee-days:3}") int guaranteeDays,
                               @Value("${crm.alerts.beo-days:7}") int beoDays,
                               @Value("${crm.alerts.milestone-days:3}") int milestoneDays) {
        this.eventRepo = eventRepo;
        this.milestoneRepo = milestoneRepo;
        this.contractRepo = contractRepo;
        this.alertService = alertService;
        this.decisionDays = decisionDays;
        this.guaranteeDays = guaranteeDays;
        this.beoDays = beoDays;
        this.milestoneDays = milestoneDays;
    }

    @Scheduled(fixedDelayString = "${crm.alerts.scan-ms:3600000}", initialDelayString = "${crm.alerts.initial-delay-ms:90000}")
    public void scheduledScan() {
        try {
            scan(LocalDate.now());
        } catch (RuntimeException ex) {
            log.warn("CRM alert scan failed: {}", ex.getMessage());
        }
    }

    public int scan(LocalDate today) {
        int raised = 0;
        for (Event e : eventRepo.findDecisionDueBetween(today, today.plusDays(decisionDays))) {
            raised += raise(e, CrmAlert.Kind.DECISION_DATE_DUE, e.getDecisionDate(),
                    "Option on " + e.getName() + " expires on " + e.getDecisionDate() + "; confirm or release the space",
                    "DECISION:" + e.getId() + ":" + e.getDecisionDate());
        }
        for (Event e : eventRepo.findGuaranteeDueBetween(today, today.plusDays(guaranteeDays))) {
            raised += raise(e, CrmAlert.Kind.GUARANTEE_DUE, e.getGuaranteeDueDate(),
                    "Guaranteed numbers for " + e.getName() + " are due on " + e.getGuaranteeDueDate(),
                    "GUARANTEE:" + e.getId() + ":" + e.getGuaranteeDueDate());
        }
        for (Event e : eventRepo.findDefiniteWithoutBeoStartingBetween(today, today.plusDays(beoDays))) {
            raised += raise(e, CrmAlert.Kind.BEO_NOT_ISSUED, e.getDateFrom(),
                    e.getName() + " starts on " + e.getDateFrom() + " and has no issued BEO",
                    "BEO:" + e.getId() + ":" + e.getDateFrom());
        }
        for (SalesPaymentMilestone m : milestoneRepo.findPlannedDueUntil(today.plusDays(milestoneDays))) {
            SalesContract contract = contractRepo.findByIdAndTenantId(m.getContractId(), m.getTenantId()).orElse(null);
            if (contract == null || contract.getStatus() == SalesContract.Status.CANCELLED) {
                continue;
            }
            Event event = eventRepo.findByIdAndTenantId(contract.getEventId(), contract.getTenantId()).orElse(null);
            String label = SalesContractService.milestoneLabel(m);
            boolean overdue = m.getDueDate().isBefore(today);
            boolean created = alertService.raise(CrmAlert.builder()
                    .tenantId(m.getTenantId())
                    .kind(CrmAlert.Kind.MILESTONE_DUE)
                    .eventId(contract.getEventId())
                    .milestoneId(m.getId())
                    .assignedTo(event != null ? event.getOwnerUserId() : null)
                    .message(label + " " + m.getAmount() + " " + contract.getCurrency() + " for "
                            + contract.getContractNumber() + (overdue ? " was due on " : " is due on ") + m.getDueDate()
                            + " and is not invoiced")
                    .dueDate(m.getDueDate())
                    .dedupeKey("MILESTONE:" + m.getId() + ":" + m.getDueDate())
                    .build());
            raised += created ? 1 : 0;
        }
        return raised;
    }

    private int raise(Event event, CrmAlert.Kind kind, LocalDate due, String message, String key) {
        return alertService.raise(CrmAlert.builder()
                .tenantId(event.getTenantId())
                .kind(kind)
                .eventId(event.getId())
                .assignedTo(event.getOwnerUserId())
                .message(message)
                .dueDate(due)
                .dedupeKey(key)
                .build()) ? 1 : 0;
    }
}
