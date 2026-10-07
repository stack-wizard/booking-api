package com.stackwizard.booking_api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;

/**
 * Seeds catalog + one won sales flow for the Opera hotel {@code DH} tenant, using the same datasource
 * as the app ({@code application-*.yaml} / SSM {@code SPRING_APPLICATION_JSON}).
 * <p>
 * Active only on {@code dev} and {@code local}. If DH is missing, logs and exits — never fails startup.
 */
@Component
@Profile({"dev", "local"})
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
            log.info("DH sales-flow seed disabled (booking.dev.dh-sales-flow-seed=false)");
            return;
        }
        try {
            tx.executeWithoutResult(status -> seed());
        } catch (Exception ex) {
            // Never block app boot on demo data — log and continue.
            log.warn("DH sales-flow seed failed (app continues): {}", ex.getMessage(), ex);
        }
    }

    private void seed() {
        Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        if (!Boolean.TRUE.equals(locked)) {
            log.info("DH sales-flow seed skipped — another instance holds the lock");
            return;
        }

        Long tenantId = jdbc.query(
                """
                select tenant_id
                from opera_hotel
                where upper(hotel_code) = 'DH' and active
                order by id
                limit 1
                """,
                rs -> rs.next() ? rs.getLong(1) : null
        );
        if (tenantId == null) {
            log.info("Opera hotel DH not found — skipping sales-flow seed (ok for non-DH environments)");
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
            log.info("DH sales-flow demo already present for tenant {} — skipping", tenantId);
            return;
        }

        log.info("Seeding DH sales flow for tenant_id={}", tenantId);
        runScript("db/seed/events_catalog.sql", tenantId);
        runScript("db/seed/dh_sales_flow.sql", tenantId);
        log.info("DH sales-flow seed done for tenant_id={}", tenantId);
    }

    private void runScript(String classpathLocation, long tenantId) {
        try {
            String sql = new ClassPathResource(classpathLocation)
                    .getContentAsString(StandardCharsets.UTF_8)
                    .replace("__TENANT_ID__", Long.toString(tenantId));
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                // Participate in the outer Spring transaction (same connection as advisory lock).
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed running seed script " + classpathLocation + ": " + ex.getMessage(), ex);
        }
    }
}
