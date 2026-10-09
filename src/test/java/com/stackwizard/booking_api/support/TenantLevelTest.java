package com.stackwizard.booking_api.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the one-tenant-id-per-row rule: every table with a {@code tenant_id} column must say whether the
 * organization or the hotel owns its rows, and the newest booking_promote_catalog list must match {@link TenantLevel#ORG_TABLES}.
 */
class TenantLevelTest {
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern CREATE = Pattern.compile(
            "create table (?:if not exists )?(\\w+)\\s*\\((.*?)\\n\\);", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "alter table (\\w+)\\s+add column (?:if not exists )?tenant_id", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_COLUMN = Pattern.compile(
            "alter table (\\w+)\\s+drop column tenant_id", Pattern.CASE_INSENSITIVE);

    @Test
    void everyTenantScopedTableIsClassified() throws IOException {
        Set<String> unclassified = new TreeSet<>();
        for (String table : tablesWithTenantId()) {
            if (TenantLevel.of(table) == null) {
                unclassified.add(table);
            }
        }
        assertThat(unclassified)
                .as("add these tables to TenantLevel (and booking_promote_catalog when they are ORG)")
                .isEmpty();
    }

    @Test
    void classifiedTablesExist() throws IOException {
        Set<String> existing = tablesWithTenantId();
        Set<String> classified = new TreeSet<>();
        classified.addAll(TenantLevel.ORG_TABLES);
        classified.addAll(TenantLevel.PROPERTY_TABLES);
        classified.addAll(TenantLevel.USER_TABLES);
        classified.addAll(TenantLevel.SYSTEM_TABLES);
        classified.removeAll(existing);
        assertThat(classified).as("classified but no tenant_id table").isEmpty();
    }

    @Test
    void promotionFunctionMatchesOrgTables() throws IOException {
        // The newest migration that defines the function wins.
        String sql;
        try (Stream<Path> stream = Files.list(MIGRATIONS)) {
            sql = stream.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparingInt(TenantLevelTest::version).reversed())
                    .map(p -> {
                        try {
                            return Files.readString(p);
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .filter(text -> text.contains("function booking_promote_catalog"))
                    .findFirst().orElseThrow();
        }
        Matcher array = Pattern.compile("org_tables text\\[\\] := array\\[(.*?)\\];", Pattern.DOTALL).matcher(sql);
        assertThat(array.find()).isTrue();
        Matcher name = Pattern.compile("'(\\w+)'").matcher(array.group(1));
        List<String> inSql = new java.util.ArrayList<>();
        while (name.find()) {
            inSql.add(name.group(1));
        }
        assertThat(inSql).containsExactlyInAnyOrderElementsOf(TenantLevel.ORG_TABLES);
    }

    private static Set<String> tablesWithTenantId() throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(MIGRATIONS)) {
            files = stream.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparingInt(TenantLevelTest::version))
                    .toList();
        }
        Set<String> tables = new TreeSet<>();
        for (Path file : files) {
            String sql = Files.readString(file);
            Matcher create = CREATE.matcher(sql);
            while (create.find()) {
                if (create.group(2).contains("tenant_id")) {
                    tables.add(create.group(1));
                }
            }
            Matcher add = ADD_COLUMN.matcher(sql);
            while (add.find()) {
                tables.add(add.group(1));
            }
            Matcher drop = DROP_COLUMN.matcher(sql);
            while (drop.find()) {
                tables.remove(drop.group(1));
            }
        }
        // Dropped in later migrations (renamed to tenant_payment_provider_config).
        tables.remove("tenant_integration_config");
        return tables;
    }

    private static int version(Path p) {
        return Integer.parseInt(p.getFileName().toString().replaceFirst("^V(\\d+)__.*", "$1"));
    }
}
