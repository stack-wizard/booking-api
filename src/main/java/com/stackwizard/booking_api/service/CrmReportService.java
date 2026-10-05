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
        Long tenantId = TenantResolver.requireTenantId();
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
        Long tenantId = TenantResolver.requireTenantId();
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

        return CrmReportDtos.ConversionResponse.builder()
                .byOwner(byOwner)
                .bySegment(bySegment)
                .build();
    }

    @Transactional(readOnly = true)
    public CrmReportDtos.StageDurationResponse stageDuration(Long pipelineId, LocalDate from, LocalDate to) {
        accessContext.require(CrmPermission.REPORT_READ);
        Long tenantId = TenantResolver.requireTenantId();
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
