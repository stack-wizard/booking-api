package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.EventDtos;
import com.stackwizard.booking_api.dto.SalesDtos;
import com.stackwizard.booking_api.model.CrmCostItem;
import com.stackwizard.booking_api.model.CrmOpportunity;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.SalesQuote;
import com.stackwizard.booking_api.repository.CrmCostItemRepository;
import com.stackwizard.booking_api.repository.CrmOpportunityRepository;
import com.stackwizard.booking_api.repository.EventRepository;
import com.stackwizard.booking_api.repository.SalesQuoteRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmOwnerScope;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Extra costs on events and opportunities, and margin = revenue - item cost - extra cost.
 * Revenue is the event's priced lines; the accepted quote total is reported next to it.
 */
@Service
public class CrmCostService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CrmCostItemRepository costRepo;
    private final EventService eventService;
    private final EventReadModelService readModelService;
    private final EventRepository eventRepo;
    private final CrmOpportunityRepository opportunityRepo;
    private final SalesQuoteRepository quoteRepo;
    private final CrmAccessContext accessContext;

    public CrmCostService(CrmCostItemRepository costRepo,
                          EventService eventService,
                          EventReadModelService readModelService,
                          EventRepository eventRepo,
                          CrmOpportunityRepository opportunityRepo,
                          SalesQuoteRepository quoteRepo,
                          CrmAccessContext accessContext) {
        this.costRepo = costRepo;
        this.eventService = eventService;
        this.readModelService = readModelService;
        this.eventRepo = eventRepo;
        this.opportunityRepo = opportunityRepo;
        this.quoteRepo = quoteRepo;
        this.accessContext = accessContext;
    }

    @Transactional(readOnly = true)
    public List<CrmCostItem> forEvent(Long eventId) {
        Event event = eventService.requireEvent(eventId);
        return costRepo.findByTenantIdAndEventIdOrderByIdAsc(event.getTenantId(), event.getId());
    }

    @Transactional(readOnly = true)
    public List<CrmCostItem> forOpportunity(Long opportunityId) {
        CrmOpportunity opportunity = requireOpportunity(opportunityId);
        return costRepo.findByTenantIdAndOpportunityIdAndEventIdIsNullOrderByIdAsc(opportunity.getTenantId(), opportunity.getId());
    }

    @Transactional
    public CrmCostItem addToEvent(Long eventId, CrmCostItem item) {
        Event event = eventService.requireEditable(eventId);
        CrmCostItem row = normalize(item, event.getTenantId(), event.getCurrency());
        row.setEventId(event.getId());
        row.setOpportunityId(event.getOpportunityId());
        return costRepo.save(row);
    }

    @Transactional
    public CrmCostItem addToOpportunity(Long opportunityId, CrmCostItem item) {
        accessContext.require(CrmPermission.OPPORTUNITY_WRITE);
        CrmOpportunity opportunity = requireOpportunity(opportunityId);
        CrmCostItem row = normalize(item, opportunity.getTenantId(), opportunity.getCurrency());
        row.setOpportunityId(opportunity.getId());
        row.setEventId(null);
        return costRepo.save(row);
    }

    @Transactional
    public CrmCostItem update(Long costId, CrmCostItem changes) {
        CrmCostItem existing = requireCostForWrite(costId);
        CrmCostItem row = normalize(changes, existing.getTenantId(), existing.getCurrency());
        existing.setCategory(row.getCategory());
        existing.setDescription(row.getDescription());
        existing.setSupplier(row.getSupplier());
        existing.setAmount(row.getAmount());
        existing.setCurrency(row.getCurrency());
        existing.setIncurredOn(row.getIncurredOn());
        return costRepo.save(existing);
    }

    @Transactional
    public void delete(Long costId) {
        costRepo.delete(requireCostForWrite(costId));
    }

    @Transactional(readOnly = true)
    public SalesDtos.Profitability eventProfitability(Long eventId) {
        Event event = eventService.requireEvent(eventId);
        SalesDtos.EventProfit profit = profit(event);
        return summary(event.getCurrency(), List.of(profit), BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public SalesDtos.Profitability opportunityProfitability(Long opportunityId) {
        CrmOpportunity opportunity = requireOpportunity(opportunityId);
        accessContext.require(CrmPermission.EVENT_READ);
        CrmOwnerScope scope = CrmOwnerScope.from(accessContext);
        List<SalesDtos.EventProfit> events = new ArrayList<>();
        for (Event event : eventRepo.findByTenantIdAndOpportunityIdOrderByDateFromAsc(opportunity.getTenantId(), opportunity.getId())) {
            if (scope.allows(event.getOwnerUserId(), event.getTeamId())) {
                events.add(profit(event));
            }
        }
        BigDecimal opportunityCost = costRepo.findByTenantIdAndOpportunityIdAndEventIdIsNullOrderByIdAsc(
                        opportunity.getTenantId(), opportunity.getId()).stream()
                .map(CrmCostItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return summary(opportunity.getCurrency(), events, opportunityCost);
    }

    SalesDtos.EventProfit profit(Event event) {
        EventDtos.Financials financials = readModelService.financials(event.getId());
        BigDecimal extra = costRepo.findByTenantIdAndEventIdOrderByIdAsc(event.getTenantId(), event.getId()).stream()
                .map(CrmCostItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal quoted = quoteRepo.findFirstByTenantIdAndEventIdAndStatusOrderByIdDesc(
                        event.getTenantId(), event.getId(), SalesQuote.Status.ACCEPTED)
                .map(SalesQuote::getTotalOffered).orElse(null);
        BigDecimal revenue = quoted != null ? quoted : financials.getTotal();
        return new SalesDtos.EventProfit(event.getId(), event.getName(), event.getStatus().name(),
                money(revenue), quoted, financials.getCostTotal(), money(extra),
                money(revenue.subtract(financials.getCostTotal()).subtract(extra)));
    }

    static SalesDtos.Profitability summary(String currency, List<SalesDtos.EventProfit> events, BigDecimal opportunityCost) {
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal quoted = BigDecimal.ZERO;
        BigDecimal itemCost = BigDecimal.ZERO;
        BigDecimal extra = opportunityCost;
        boolean anyQuoted = false;
        for (SalesDtos.EventProfit e : events) {
            revenue = revenue.add(e.revenue());
            if (e.quotedTotal() != null) {
                quoted = quoted.add(e.quotedTotal());
                anyQuoted = true;
            }
            itemCost = itemCost.add(e.itemCost());
            extra = extra.add(e.extraCost());
        }
        BigDecimal margin = revenue.subtract(itemCost).subtract(extra);
        BigDecimal marginPercent = revenue.signum() == 0 ? null
                : margin.multiply(HUNDRED).divide(revenue, 2, RoundingMode.HALF_UP);
        return new SalesDtos.Profitability(currency, money(revenue), anyQuoted ? money(quoted) : null,
                money(itemCost), money(extra), money(margin), marginPercent, events);
    }

    private CrmCostItem normalize(CrmCostItem item, Long tenantId, String defaultCurrency) {
        if (item == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (!StringUtils.hasText(item.getDescription())) {
            throw new IllegalArgumentException("description is required");
        }
        if (item.getAmount() == null || item.getAmount().signum() < 0) {
            throw new IllegalArgumentException("amount must be >= 0");
        }
        return CrmCostItem.builder()
                .tenantId(tenantId)
                .category(item.getCategory() != null ? item.getCategory() : CrmCostItem.Category.OTHER)
                .description(item.getDescription().trim())
                .supplier(StringUtils.hasText(item.getSupplier()) ? item.getSupplier().trim() : null)
                .amount(money(item.getAmount()))
                .currency(StringUtils.hasText(item.getCurrency()) ? item.getCurrency().trim().toUpperCase()
                        : defaultCurrency != null ? defaultCurrency : "EUR")
                .incurredOn(item.getIncurredOn())
                .createdBy(accessContext.currentUserId())
                .build();
    }

    private CrmCostItem requireCostForWrite(Long costId) {
        CrmCostItem cost = costRepo.findByIdAndTenantId(costId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cost not found"));
        if (cost.getEventId() != null) {
            eventService.requireEditable(cost.getEventId());
        } else {
            accessContext.require(CrmPermission.OPPORTUNITY_WRITE);
            requireOpportunity(cost.getOpportunityId());
        }
        return cost;
    }

    private CrmOpportunity requireOpportunity(Long opportunityId) {
        accessContext.require(CrmPermission.OPPORTUNITY_READ);
        return opportunityRepo.findByIdAndTenantId(opportunityId, TenantResolver.requireTenantId())
                .filter(o -> CrmOwnerScope.from(accessContext).allows(o.getOwnerUserId(), o.getTeamId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Opportunity not found"));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
