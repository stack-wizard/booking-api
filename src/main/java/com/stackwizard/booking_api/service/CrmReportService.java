package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.CrmReportDtos;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
public class CrmReportService {
    private final EntityManager entityManager;
    private final CrmAccessContext accessContext;

    public CrmReportService(EntityManager entityManager, CrmAccessContext accessContext) {
        this.entityManager = entityManager;
        this.accessContext = accessContext;
    }

    @Transactional(readOnly = true)
    public CrmReportDtos.FunnelResponse funnel(Long pipelineId, LocalDate from, LocalDate to, Long ownerUserId) {
        accessContext.require(CrmPermission.REPORT_READ);
        Long tenantId = TenantResolver.requireOrgTenantId();
        String sql = """
                select s.id, s.name, s.display_order,
                       count(distinct t.opportunity_id) as entered,
                       count(distinct case when later.id is not null then t.opportunity_id end) as advanced
                from crm_pipeline_stage s
                left join (crm_stage_transition t
                           join crm_opportunity o
                             on o.id = t.opportunity_id
                            and (cast(:ownerUserId as bigint) is null or o.owner_user_id = cast(:ownerUserId as bigint)))
                  on t.to_stage_id = s.id and t.tenant_id = s.tenant_id
                 and (cast(:fromTs as timestamptz) is null or t.changed_at >= cast(:fromTs as timestamptz))
                 and (cast(:toTs as timestamptz) is null or t.changed_at < cast(:toTs as timestamptz))
                left join crm_stage_transition later
                  on later.opportunity_id = t.opportunity_id
                 and later.from_stage_id = s.id
                 and later.changed_at > t.changed_at
                where s.tenant_id = :tenantId
                  and s.pipeline_id = :pipelineId
                group by s.id, s.name, s.display_order
                order by s.display_order
                """;
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("tenantId", tenantId);
        query.setParameter("pipelineId", pipelineId);
        query.setParameter("fromTs", toStart(from));
        query.setParameter("toTs", toEndExclusive(to));
        query.setParameter("ownerUserId", ownerUserId);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<CrmReportDtos.FunnelStageRow> stages = new ArrayList<>();
        for (Object[] row : rows) {
            long entered = ((Number) row[3]).longValue();
            long advanced = ((Number) row[4]).longValue();
            stages.add(CrmReportDtos.FunnelStageRow.builder()
                    .stageId(((Number) row[0]).longValue())
                    .stageName((String) row[1])
                    .displayOrder(((Number) row[2]).intValue())
                    .entered(entered)
                    .advanced(advanced)
                    .stuck(Math.max(0, entered - advanced))
                    .build());
        }
        return CrmReportDtos.FunnelResponse.builder().stages(stages).build();
    }

    @Transactional(readOnly = true)
    public CrmReportDtos.ConversionResponse conversion(LocalDate from, LocalDate to) {
        accessContext.require(CrmPermission.REPORT_READ);
        Long tenantId = TenantResolver.requireOrgTenantId();
        List<CrmReportDtos.ConversionRow> byOwner = conversionRows("""
                select coalesce(cast(o.owner_user_id as text), 'unassigned') as dim_key,
                       count(*) filter (where o.status = 'WON') as won,
                       count(*) filter (where o.status = 'LOST') as lost,
                       count(*) filter (where o.status = 'TURNED_DOWN') as turned_down,
                       count(*) filter (where o.status = 'CANCELLED') as cancelled
                from crm_opportunity o
                where o.tenant_id = :tenantId
                  and o.status in ('WON','LOST','TURNED_DOWN','CANCELLED')
                  and (cast(:fromTs as timestamptz) is null or o.closed_at >= cast(:fromTs as timestamptz))
                  and (cast(:toTs as timestamptz) is null or o.closed_at < cast(:toTs as timestamptz))
                group by o.owner_user_id
                order by 1
                """, tenantId, from, to, "owner");

        List<CrmReportDtos.ConversionRow> bySegment = conversionRows("""
                select coalesce(a.segment, 'unspecified') as dim_key,
                       count(*) filter (where o.status = 'WON') as won,
                       count(*) filter (where o.status = 'LOST') as lost,
                       count(*) filter (where o.status = 'TURNED_DOWN') as turned_down,
                       count(*) filter (where o.status = 'CANCELLED') as cancelled
                from crm_opportunity o
                join crm_account a on a.id = o.account_id
                where o.tenant_id = :tenantId
                  and o.status in ('WON','LOST','TURNED_DOWN','CANCELLED')
                  and (cast(:fromTs as timestamptz) is null or o.closed_at >= cast(:fromTs as timestamptz))
                  and (cast(:toTs as timestamptz) is null or o.closed_at < cast(:toTs as timestamptz))
                group by a.segment
                order by 1
                """, tenantId, from, to, "segment");

        List<CrmReportDtos.ConversionRow> byPipeline = conversionRows("""
                select p.name as dim_key,
                       count(*) filter (where o.status = 'WON') as won,
                       count(*) filter (where o.status = 'LOST') as lost,
                       count(*) filter (where o.status = 'TURNED_DOWN') as turned_down,
                       count(*) filter (where o.status = 'CANCELLED') as cancelled
                from crm_opportunity o
                join crm_pipeline p on p.id = o.pipeline_id
                where o.tenant_id = :tenantId
                  and o.status in ('WON','LOST','TURNED_DOWN','CANCELLED')
                  and (cast(:fromTs as timestamptz) is null or o.closed_at >= cast(:fromTs as timestamptz))
                  and (cast(:toTs as timestamptz) is null or o.closed_at < cast(:toTs as timestamptz))
                group by p.name
                order by 1
                """, tenantId, from, to, "pipeline");

        return CrmReportDtos.ConversionResponse.builder()
                .byOwner(byOwner)
                .bySegment(bySegment)
                .byPipeline(byPipeline)
                .build();
    }

    /**
     * DEFINITE and ACTUAL events starting in the range. Revenue is the accepted quote total, otherwise the
     * event's priced lines; cost is item cost plus crm_cost_item.
     */
    @Transactional(readOnly = true)
    public CrmReportDtos.RevenueResponse revenue(LocalDate from, LocalDate to) {
        accessContext.require(CrmPermission.REPORT_READ);
        Long tenantId = TenantResolver.requireOrgTenantId();
        String sql = """
                with ev as (
                  select e.id, e.account_id,
                         coalesce(q.total_offered,
                                  coalesce((select sum(r.gross_amount) from reservation r
                                            join event_function f on f.id = r.event_function_id
                                            where f.event_id = e.id and r.status <> 'CANCELLED'), 0)
                                + coalesce((select sum(i.gross_amount) from event_function_item i
                                            join event_function f on f.id = i.event_function_id
                                            where f.event_id = e.id), 0)) as revenue,
                         coalesce((select sum(i.cost_amount) from event_function_item i
                                   join event_function f on f.id = i.event_function_id
                                   where f.event_id = e.id), 0)
                       + coalesce((select sum(c.amount) from crm_cost_item c where c.event_id = e.id), 0) as cost
                  from event e
                  left join lateral (
                    select sq.total_offered from sales_quote sq
                    where sq.event_id = e.id and sq.status = 'ACCEPTED'
                    order by sq.id desc limit 1
                  ) q on true
                  where e.tenant_id = :tenantId
                    and e.status in ('DEFINITE','ACTUAL')
                    and (cast(:fromDate as date) is null or e.date_from >= cast(:fromDate as date))
                    and (cast(:toDate as date) is null or e.date_from <= cast(:toDate as date))
                )
                select a.id, a.name, coalesce(a.segment, 'unspecified') as segment,
                       count(ev.id), coalesce(sum(ev.revenue), 0), coalesce(sum(ev.cost), 0)
                from ev
                join crm_account a on a.id = ev.account_id
                group by a.id, a.name, a.segment
                order by 5 desc, a.name
                """;
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("tenantId", tenantId);
        query.setParameter("fromDate", from);
        query.setParameter("toDate", to);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<CrmReportDtos.RevenueRow> byAccount = new ArrayList<>();
        java.util.Map<String, BigDecimal[]> segments = new java.util.TreeMap<>();
        java.util.Map<String, Long> segmentEvents = new java.util.HashMap<>();
        for (Object[] row : rows) {
            BigDecimal revenue = toDecimal(row[4]);
            BigDecimal cost = toDecimal(row[5]);
            long events = ((Number) row[3]).longValue();
            byAccount.add(revenueRow("account", (String) row[1], ((Number) row[0]).longValue(), events, revenue, cost));
            String segment = (String) row[2];
            BigDecimal[] sum = segments.computeIfAbsent(segment, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            sum[0] = sum[0].add(revenue);
            sum[1] = sum[1].add(cost);
            segmentEvents.merge(segment, events, Long::sum);
        }
        List<CrmReportDtos.RevenueRow> bySegment = segments.entrySet().stream()
                .map(e -> revenueRow("segment", e.getKey(), null, segmentEvents.get(e.getKey()), e.getValue()[0], e.getValue()[1]))
                .toList();
        return CrmReportDtos.RevenueResponse.builder().byAccount(byAccount).bySegment(bySegment).build();
    }

    /**
     * Booked hours (active allocations clipped to the range) against open hours of the booking calendar
     * for every space that has setup capacities.
     */
    @Transactional(readOnly = true)
    public CrmReportDtos.UtilisationResponse spaceUtilisation(LocalDate from, LocalDate to) {
        accessContext.require(CrmPermission.REPORT_READ);
        Long tenantId = TenantResolver.requireOrgTenantId();
        LocalDate start = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate end = to != null ? to : start.plusMonths(1).minusDays(1);
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("to must not be before from");
        }
        int days = (int) java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        String sql = """
                with spaces as (
                  select distinct r.id, r.name, r.location_id
                  from resource r
                  join resource_setup_capacity c on c.resource_id = r.id and c.tenant_id = r.tenant_id
                  where r.tenant_id = :tenantId
                ),
                booked as (
                  select a.allocated_resource_id as resource_id,
                         sum(extract(epoch from (least(a.ends_at, cast(:rangeEnd as timestamp))
                                               - greatest(a.starts_at, cast(:rangeStart as timestamp)))) / 3600.0) as hours
                  from allocation a
                  where a.tenant_id = :tenantId
                    and a.starts_at < cast(:rangeEnd as timestamp)
                    and a.ends_at > cast(:rangeStart as timestamp)
                    and (upper(a.status) = 'CONFIRMED'
                         or (upper(a.status) = 'HOLD' and (a.expires_at is null or a.expires_at > now())))
                  group by a.allocated_resource_id
                )
                select s.id, s.name, coalesce(b.hours, 0),
                       coalesce((select extract(epoch from (bc.close_time - bc.open_time)) / 3600.0
                                 from booking_calendar bc
                                 where bc.tenant_id = :tenantId
                                   and (bc.location_node_id = s.location_id or bc.location_node_id is null)
                                 order by (bc.location_node_id is null), bc.id
                                 limit 1), 24) as open_hours
                from spaces s
                left join booked b on b.resource_id = s.id
                order by s.name
                """;
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("tenantId", tenantId);
        query.setParameter("rangeStart", start.atStartOfDay());
        query.setParameter("rangeEnd", end.plusDays(1).atStartOfDay());
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<CrmReportDtos.UtilisationRow> spaces = new ArrayList<>();
        for (Object[] row : rows) {
            double booked = ((Number) row[2]).doubleValue();
            double openHours = ((Number) row[3]).doubleValue();
            double available = openHours > 0 ? openHours * days : 24.0 * days;
            spaces.add(CrmReportDtos.UtilisationRow.builder()
                    .resourceId(((Number) row[0]).longValue())
                    .resourceName((String) row[1])
                    .bookedHours(Math.round(booked * 100) / 100.0)
                    .availableHours(Math.round(available * 100) / 100.0)
                    .utilisation(BigDecimal.valueOf(Math.min(1.0, booked / available)).setScale(4, RoundingMode.HALF_UP))
                    .build());
        }
        return CrmReportDtos.UtilisationResponse.builder().days(days).spaces(spaces).build();
    }

    static CrmReportDtos.RevenueRow revenueRow(String dimension, String key, Long accountId, long events,
                                               BigDecimal revenue, BigDecimal cost) {
        BigDecimal margin = revenue.subtract(cost);
        return CrmReportDtos.RevenueRow.builder()
                .dimension(dimension)
                .key(key)
                .accountId(accountId)
                .events(events)
                .revenue(revenue.setScale(2, RoundingMode.HALF_UP))
                .cost(cost.setScale(2, RoundingMode.HALF_UP))
                .margin(margin.setScale(2, RoundingMode.HALF_UP))
                .marginPercent(revenue.signum() == 0 ? null
                        : margin.multiply(BigDecimal.valueOf(100)).divide(revenue, 2, RoundingMode.HALF_UP))
                .build();
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    @Transactional(readOnly = true)
    public CrmReportDtos.StageDurationResponse stageDuration(Long pipelineId, LocalDate from, LocalDate to) {
        accessContext.require(CrmPermission.REPORT_READ);
        Long tenantId = TenantResolver.requireOrgTenantId();
        String sql = """
                with ordered as (
                  select t.from_stage_id,
                         t.changed_at,
                         lag(t.changed_at) over (partition by t.opportunity_id order by t.changed_at, t.id) as prev_changed_at
                  from crm_stage_transition t
                  where t.tenant_id = :tenantId
                ),
                spans as (
                  select x.from_stage_id as stage_id,
                         extract(epoch from (x.changed_at - x.prev_changed_at)) / 3600.0 as hours
                  from ordered x
                  join crm_pipeline_stage s on s.id = x.from_stage_id
                  where s.pipeline_id = :pipelineId
                    and x.prev_changed_at is not null
                    and (cast(:fromTs as timestamptz) is null or x.changed_at >= cast(:fromTs as timestamptz))
                    and (cast(:toTs as timestamptz) is null or x.changed_at < cast(:toTs as timestamptz))
                )
                select s.id, s.name,
                       avg(sp.hours) as avg_hours,
                       percentile_cont(0.5) within group (order by sp.hours) as median_hours,
                       count(sp.hours) as samples
                from crm_pipeline_stage s
                left join spans sp on sp.stage_id = s.id
                where s.tenant_id = :tenantId and s.pipeline_id = :pipelineId
                group by s.id, s.name, s.display_order
                order by s.display_order
                """;
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("tenantId", tenantId);
        query.setParameter("pipelineId", pipelineId);
        query.setParameter("fromTs", toStart(from));
        query.setParameter("toTs", toEndExclusive(to));
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<CrmReportDtos.StageDurationRow> stages = new ArrayList<>();
        for (Object[] row : rows) {
            stages.add(CrmReportDtos.StageDurationRow.builder()
                    .stageId(((Number) row[0]).longValue())
                    .stageName((String) row[1])
                    .avgHours(row[2] == null ? null : ((Number) row[2]).doubleValue())
                    .medianHours(row[3] == null ? null : ((Number) row[3]).doubleValue())
                    .samples(row[4] == null ? 0L : ((Number) row[4]).longValue())
                    .build());
        }
        return CrmReportDtos.StageDurationResponse.builder().stages(stages).build();
    }

    private List<CrmReportDtos.ConversionRow> conversionRows(String sql, Long tenantId, LocalDate from, LocalDate to, String dimension) {
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("tenantId", tenantId);
        query.setParameter("fromTs", toStart(from));
        query.setParameter("toTs", toEndExclusive(to));
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<CrmReportDtos.ConversionRow> out = new ArrayList<>();
        for (Object[] row : rows) {
            long won = ((Number) row[1]).longValue();
            long lost = ((Number) row[2]).longValue();
            long turnedDown = ((Number) row[3]).longValue();
            long cancelled = ((Number) row[4]).longValue();
            long denom = won + lost + turnedDown;
            BigDecimal winRate = denom == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(won).divide(BigDecimal.valueOf(denom), 4, RoundingMode.HALF_UP);
            out.add(CrmReportDtos.ConversionRow.builder()
                    .dimension(dimension)
                    .key(String.valueOf(row[0]))
                    .won(won)
                    .lost(lost)
                    .turnedDown(turnedDown)
                    .cancelled(cancelled)
                    .winRate(winRate)
                    .build());
        }
        return out;
    }

    private static OffsetDateTime toStart(LocalDate from) {
        return from == null ? null : from.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime toEndExclusive(LocalDate to) {
        return to == null ? null : to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
