package com.stackwizard.booking_api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.UUID;

/**
 * Seeds catalog + one won sales flow for a Platform tenant, using the app datasource
 * ({@code application-*.yaml} / SSM {@code SPRING_APPLICATION_JSON}).
 * <p>
 * Resolves Platform UUID → local {@code tenant_id} via {@code platform_tenant_mapping}
 * (same path as {@code X-Tenant-Id}). Catalog and CRM go to the chain, spaces and functions to the hotel.
 * <p>
 * After parent/child tenancy, an older seed on the hotel alone is wiped and re-seeded onto the organization
 * with {@code property_tenant_id} set. A correct seed is left alone. Gated by
 * {@code booking.dev.dh-sales-flow-seed} (default true). Missing mapping → log and skip. Failures never
 * block startup.
 */
@Component
@Order(1000)
public class DhSalesFlowSeedRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DhSalesFlowSeedRunner.class);
    /** Stable advisory lock key so concurrent ECS tasks do not double-insert. */
    private static final long ADVISORY_LOCK_KEY = 872_014_550_1L;

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BookingDevProperties properties;

    public DhSalesFlowSeedRunner(
            DataSource dataSource,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            BookingDevProperties properties
    ) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isDhSalesFlowSeed()) {
            log.info("Demo sales-flow seed disabled (booking.dev.dh-sales-flow-seed=false)");
            return;
        }
        try {
            tx.executeWithoutResult(status -> seed());
        } catch (Exception ex) {
            log.warn("Demo sales-flow seed failed (app continues): {}", ex.getMessage(), ex);
        }
    }

    private void seed() {
        Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        if (!Boolean.TRUE.equals(locked)) {
            log.info("Demo sales-flow seed skipped — another instance holds the lock");
            return;
        }

        UUID platformTenantId = properties.getDhSalesFlowPlatformTenantId();
        if (platformTenantId == null) {
            log.info("Demo sales-flow platform tenant id is not set — skipping");
            return;
        }

        Scope scope = resolveScope(platformTenantId);
        if (scope == null) {
            log.info(
                    "Platform tenant {} is not mapped yet — skipping sales-flow seed "
                            + "(onboard the tenant in booking-admin /setup first, then restart)",
                    platformTenantId
            );
            return;
        }

        if (isCorrectSeedPresent(scope.orgTenantId(), scope.propertyTenantId())) {
            log.info(
                    "Demo sales-flow already present for org={} property={} (platform {}) — skipping",
                    scope.orgTenantId(), scope.propertyTenantId(), platformTenantId
            );
            return;
        }

        log.info(
                "Resetting demo sales-flow for platform={} → org={} property={}",
                platformTenantId, scope.orgTenantId(), scope.propertyTenantId()
        );
        runScript("db/seed/dh_sales_flow_wipe.sql", scope.orgTenantId(), scope.propertyTenantId());
        runScript("db/seed/events_catalog.sql", scope.orgTenantId(), scope.propertyTenantId());
        runScript("db/seed/dh_sales_flow.sql", scope.orgTenantId(), scope.propertyTenantId());
        log.info("Demo sales-flow seed done for org={}", scope.orgTenantId());
    }

    private record Scope(long orgTenantId, long propertyTenantId) {
    }

    private Scope resolveScope(UUID platformTenantId) {
        return jdbc.query(
                """
                select m.tenant_id, m.kind, m.parent_tenant_id
                from platform_tenant_mapping m
                where m.platform_tenant_id = ?
                limit 1
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    long tenantId = rs.getLong(1);
                    String kind = rs.getString(2);
                    long parentTenantId = rs.getLong(3);
                    boolean hasParent = !rs.wasNull();
                    if ("PROPERTY".equals(kind) && hasParent) {
                        return new Scope(parentTenantId, tenantId);
                    }
                    long propertyTenantId = firstHotelOf(tenantId);
                    return new Scope(tenantId, propertyTenantId);
                },
                platformTenantId
        );
    }

    /** True when the demo event sits on the organization and points at the hotel. */
    private boolean isCorrectSeedPresent(long orgTenantId, long propertyTenantId) {
        Integer count = jdbc.queryForObject(
                """
                select count(*)::int from event
                where tenant_id = ?
                  and attrs->>'seed' = 'DH_SALES_FLOW'
                  and property_tenant_id is not distinct from ?
                """,
                Integer.class,
                orgTenantId,
                propertyTenantId
        );
        return count != null && count > 0;
    }

    private long firstHotelOf(long orgTenantId) {
        Long hotel = jdbc.query(
                """
                select tenant_id from platform_tenant_mapping
                where parent_tenant_id = ?
                order by hotel_code, tenant_id
                limit 1
                """,
                rs -> rs.next() ? rs.getLong(1) : null,
                orgTenantId
        );
        return hotel != null ? hotel : orgTenantId;
    }

    private void runScript(String classpathLocation, long orgTenantId, long propertyTenantId) {
        try {
            String sql = new ClassPathResource(classpathLocation)
                    .getContentAsString(StandardCharsets.UTF_8)
                    .replace("__ORG_TENANT_ID__", Long.toString(orgTenantId))
                    .replace("__PROPERTY_TENANT_ID__", Long.toString(propertyTenantId))
                    .replace("__TENANT_ID__", Long.toString(orgTenantId));
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed running seed script " + classpathLocation + ": " + ex.getMessage(), ex);
        }
    }
}
