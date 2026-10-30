package com.stackwizard.booking_api.service.loyalty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.LoyaltyImportBatch;
import com.stackwizard.booking_api.model.LoyaltyProgram;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.repository.LoyaltyImportBatchRepository;
import com.stackwizard.booking_api.repository.LoyaltyImportFileRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import com.stackwizard.booking_api.service.loyalty.imports.ImportStorage;
import com.stackwizard.booking_api.service.loyalty.imports.LoyaltyImportService;
import com.stackwizard.booking_api.service.loyalty.imports.LoyaltyImportWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Imports with {@code loyalty.import.storage=DB}: the CSV goes through the API into loyalty_import_file, the
 * worker reads it from there, and the copy is dropped once the batch is done. Needs LOYALTY_SCRATCH_DB_URL like
 * {@link LoyaltyFlowScratchDbIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "LOYALTY_SCRATCH_DB_URL", matches = ".+")
class LoyaltyImportDbStorageScratchDbIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("LOYALTY_SCRATCH_DB_URL"));
        registry.add("spring.docker.compose.enabled", () -> "false");
        registry.add("loyalty.import.enabled", () -> "false");
        registry.add("loyalty.import.storage", () -> "DB");
        registry.add("loyalty.evaluation.enabled", () -> "false");
        registry.add("loyalty.expiry.enabled", () -> "false");
        registry.add("integration.events.dispatch-enabled", () -> "false");
    }

    @MockitoBean ImportStorage storage;
    @Autowired LoyaltyProgramService programService;
    @Autowired LoyaltyImportService importService;
    @Autowired LoyaltyImportWorker importWorker;
    @Autowired LoyaltyImportBatchRepository batchRepo;
    @Autowired LoyaltyImportFileRepository fileRepo;
    @Autowired LoyaltyMemberRepository memberRepo;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void fileLivesInTheDatabaseUntilTheBatchIsDone() {
        Long org = jdbc.queryForObject("select tenant_id from platform_tenant_mapping where kind = 'ORG' order by tenant_id limit 1", Long.class);
        String run = UUID.randomUUID().toString().substring(0, 6);
        LoyaltyProgram program = program(org, run);
        String number = "8" + Math.abs(run.hashCode() % 100000) + "01";
        byte[] csv = ("Broj kartice;Ime;Prezime;E-mail;Level;Realized room nights\n"
                + number + ";Iva;Ivić;iva." + run + "@example.com;Gold;40\n").getBytes(StandardCharsets.UTF_8);

        LoyaltyImportService.Upload upload = importService.create(org, program.getId(), LoyaltyImportBatch.ImportType.MEMBERS, null, "m.csv", null, null);
        assertThat(upload.uploadUrl()).isNull();
        assertThat(upload.method()).isEqualTo("POST");
        assertThat(upload.uploadPath()).isEqualTo("/api/loyalty/imports/" + upload.batch().getId() + "/file");
        Long id = upload.batch().getId();

        assertThatThrownBy(() -> importService.start(org, id, null, om.createObjectNode()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not uploaded");

        LoyaltyImportBatch uploaded = importService.uploadFile(org, id, csv, "text/csv");
        assertThat(uploaded.getFileSize()).isEqualTo((long) csv.length);
        assertThat(fileRepo.contentLength(id)).contains((long) csv.length);

        ObjectNode options = om.createObjectNode().put("asOf", "2026-10-01");
        importService.start(org, id, null, options);
        importWorker.drain();
        LoyaltyImportBatch validated = batchRepo.findById(id).orElseThrow();
        assertThat(validated.getStatus()).isEqualTo(LoyaltyImportBatch.Status.VALIDATED);
        assertThat(validated.getRowsValid()).isEqualTo(1);

        importService.apply(org, id);
        importWorker.drain();
        assertThat(batchRepo.findById(id).orElseThrow().getStatus()).isEqualTo(LoyaltyImportBatch.Status.DONE);
        assertThat(fileRepo.existsById(id)).as("database copy dropped after apply").isFalse();
        assertThat(memberRepo.findByProgramIdAndMemberNumber(program.getId(), number)).isPresent();
        verifyNoInteractions(storage);

        // a cancelled batch drops its file too
        Long other = importService.create(org, program.getId(), LoyaltyImportBatch.ImportType.MEMBERS, null, "x.csv", null, null).batch().getId();
        importService.uploadFile(org, other, csv, "text/csv");
        assertThat(fileRepo.existsById(other)).isTrue();
        importService.cancel(org, other);
        assertThat(fileRepo.existsById(other)).isFalse();
    }

    private LoyaltyProgram program(Long org, String run) {
        LoyaltyProgram p = programService.create(org, LoyaltyProgram.builder().code("ITDB_" + run).name("DB storage IT " + run).build());
        ObjectNode params = om.createObjectNode().put("validity_months", 24);
        var draft = programService.createDraft(org, p.getId(), LocalDate.of(2019, 1, 1), LoyaltyRuleSet.TierPolicy.ROLLING_VALIDITY, params, "v1", null);
        programService.updateDraft(org, draft.ruleSet().getId(), null, null, null, null,
                List.of(new LoyaltyProgramService.TierInput("LIGHT", "Light", 0, 0, null),
                        new LoyaltyProgramService.TierInput("GOLD", "Gold", 1, 31, null)),
                List.of());
        programService.activate(org, draft.ruleSet().getId());
        return p;
    }
}
