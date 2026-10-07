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
 * (same path as {@code X-Tenant-Id}). Not an Opera hotel code. Mapping is created automatically
 * on first admin login — there is no mapping screen in the UI.
 * <p>
 * Gated by {@code booking.dev.dh-sales-flow-seed} (default true). Missing mapping → log and skip.
 * Failures never block startup.
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

        Long tenantId = jdbc.query(
                """
                select tenant_id
                from platform_tenant_mapping
                where platform_tenant_id = ?
                limit 1
                """,
                rs -> rs.next() ? rs.getLong(1) : null,
                platformTenantId
        );
        if (tenantId == null) {
            log.info(
                    "Platform tenant {} is not mapped yet — skipping sales-flow seed "
                            + "(log into booking-admin with that tenant once, then restart)",
                    platformTenantId
            );
            return;
        }

        Integer already = jdbc.queryForObject(
                """
                select count(*)::int from event
                where tenant_id = ? and attrs->>'seed' = 'DH_SALES_FLOW'
                """,
                Integer.class,
                tenantId
        );
        if (already != null && already > 0) {
            log.info("Demo sales-flow already present for tenant_id={} (platform {}) — skipping", tenantId, platformTenantId);
            return;
        }

        log.info("Seeding demo sales flow for platform={} → tenant_id={}", platformTenantId, tenantId);
        runScript("db/seed/events_catalog.sql", tenantId);
        runScript("db/seed/dh_sales_flow.sql", tenantId);
        log.info("Demo sales-flow seed done for tenant_id={}", tenantId);
    }

    private void runScript(String classpathLocation, long tenantId) {
        try {
            String sql = new ClassPathResource(classpathLocation)
                    .getContentAsString(StandardCharsets.UTF_8)
                    .replace("__TENANT_ID__", Long.toString(tenantId));
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
