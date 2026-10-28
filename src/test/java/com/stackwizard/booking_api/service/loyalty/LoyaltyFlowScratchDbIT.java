package com.stackwizard.booking_api.service.loyalty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.LoyaltyChannelMap;
import com.stackwizard.booking_api.model.LoyaltyImportBatch;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.LoyaltyProgram;
import com.stackwizard.booking_api.model.LoyaltyQualificationStep.RuleCode;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.repository.LoyaltyImportBatchRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import com.stackwizard.booking_api.service.integration.EventSubscriptionService;
import com.stackwizard.booking_api.service.loyalty.imports.ImportStorage;
import com.stackwizard.booking_api.service.loyalty.imports.LoyaltyImportService;
import com.stackwizard.booking_api.service.loyalty.imports.LoyaltyImportWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end loyalty flow on a scratch copy of a real database (never the dev DB): Arena rule set, member
 * migration import, OPERA reservation import, qualification, reversal, re-import idempotency, outbox, expiry.
 * Run with {@code LOYALTY_SCRATCH_DB_URL=jdbc:postgresql://localhost:5432/<scratch>}. S3 is replaced by an
 * in-memory map; schedulers are off and the workers are driven by hand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "LOYALTY_SCRATCH_DB_URL", matches = ".+")
class LoyaltyFlowScratchDbIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("LOYALTY_SCRATCH_DB_URL"));
        registry.add("spring.docker.compose.enabled", () -> "false");
        registry.add("loyalty.import.enabled", () -> "false");
        registry.add("loyalty.evaluation.enabled", () -> "false");
        registry.add("loyalty.expiry.enabled", () -> "false");
        registry.add("integration.events.dispatch-enabled", () -> "false");
    }

    @MockitoBean ImportStorage storage;
    @Autowired LoyaltyProgramService programService;
    @Autowired LoyaltyImportService importService;
    @Autowired LoyaltyImportWorker importWorker;
    @Autowired LoyaltyEvaluationWorker evaluationWorker;
    @Autowired LoyaltyLedgerService ledgerService;
    @Autowired LoyaltyMemberService memberService;
    @Autowired LoyaltyExpiryJob expiryJob;
    @Autowired EventSubscriptionService subscriptionService;
    @Autowired LoyaltyMemberRepository memberRepo;
    @Autowired LoyaltyImportBatchRepository batchRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, String> files = new HashMap<>();
    private Long org;
    private Long hotel;
    private String run;

    @BeforeEach
    void setUp() {
        org = jdbc.queryForObject("select tenant_id from platform_tenant_mapping where kind = 'ORG' order by tenant_id limit 1", Long.class);
        hotel = jdbc.queryForObject("select tenant_id from platform_tenant_mapping where parent_tenant_id = ? order by tenant_id limit 1", Long.class, org);
        run = UUID.randomUUID().toString().substring(0, 6);
        when(storage.keyFor(anyLong(), anyLong())).thenAnswer(i -> "it/" + i.getArgument(0) + "/" + i.getArgument(1) + ".csv");
        when(storage.presignPut(anyString())).thenReturn("http://upload.invalid/put");
        when(storage.size(anyString())).thenAnswer(i -> Optional.ofNullable(files.get(i.<String>getArgument(0))).map(s -> (long) s.length()));
        when(storage.open(anyString())).thenAnswer(i -> new ByteArrayInputStream(files.get(i.<String>getArgument(0)).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void fullFlow() {
        LoyaltyProgram program = arenaProgram();
        subscriptionService.create(org, "it-" + run, "https://hooks.invalid/" + run, List.of("loyalty.*"));
        long eventsBefore = count("select count(*) from integration_event where tenant_id = ?", org);

        // --- members (migration) ------------------------------------------------------------------------
        String n1 = "9" + Math.abs(run.hashCode() % 100000) + "01";
        String n2 = "9" + Math.abs(run.hashCode() % 100000) + "02";
        String members = "﻿Broj kartice;Ime;Prezime;E-mail;Level;Realized room nights;Nights this year;Status valid until;Wallet;Newsletter\n"
                + n1 + ";Marko;Čukon;marko." + run + "@example.com;Platinum;73;5;2027-06-30;da;da\n"
                + n2 + ";Ana;Horvat;ana." + run + "@example.com;Silver;12;0;2027-01-15;ne;ne\n"
                + "x;Bad;Mail;not-an-email;;1;;;;\n"
                + n1 + ";Dup;Licate;dup." + run + "@example.com;;1;;;;\n";
        LoyaltyImportBatch mb = importAndValidate(program, LoyaltyImportBatch.ImportType.MEMBERS, null, members, om.createObjectNode());
        assertThat(mb.getStatus()).isEqualTo(LoyaltyImportBatch.Status.VALIDATED);
        assertThat(mb.getRowsTotal()).isEqualTo(4);
        assertThat(mb.getRowsValid()).isEqualTo(2);
        assertThat(mb.getRowsInvalid()).isEqualTo(2);
        assertThat(importService.errorReport(org, mb.getId())).contains("email is not valid").contains("appears more than once");
        mb = applyBatch(mb);
        assertThat(mb.getStatus()).isEqualTo(LoyaltyImportBatch.Status.DONE);
        assertThat(mb.getRowsApplied()).isEqualTo(2);

        LoyaltyMember marko = memberRepo.findByProgramIdAndMemberNumber(program.getId(), n1).orElseThrow();
        LoyaltyMember ana = memberRepo.findByProgramIdAndMemberNumber(program.getId(), n2).orElseThrow();
        assertThat(marko.getNightsTotal()).isEqualTo(73);
        assertThat(marko.getNightsCurrentYear()).isEqualTo(5);
        assertThat(tierCode(marko)).isEqualTo("PLATINUM");
        assertThat(marko.getTierOverride()).isFalse();
        assertThat(marko.getTierValidUntil()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(marko.getWalletAddedAt()).isNotNull();
        assertThat(tierCode(ana)).isEqualTo("SILVER");
        assertThat(ana.getWalletAddedAt()).isNull();
        assertThat(count("select count(*) from integration_event where tenant_id = ?", org)).as("migration is silent").isEqualTo(eventsBefore);
        // the running number moved past the imported cards
        assertThat(programService.require(org, program.getId()).getMemberNumberNext()).isGreaterThan(Long.parseLong(n2));

        // re-import of the same file changes nothing
        LoyaltyImportBatch again = applyBatch(importAndValidate(program, LoyaltyImportBatch.ImportType.MEMBERS, null, members, om.createObjectNode()));
        assertThat(again.getStatus()).isEqualTo(LoyaltyImportBatch.Status.DONE);
        assertThat(count("select count(*) from loyalty_ledger where member_id = ? and active", marko.getId())).isEqualTo(2);
        assertThat(reload(marko).getNightsTotal()).isEqualTo(73);

        // --- reservations (OPERA export) ----------------------------------------------------------------
        String c = "R" + run;
        String reservations = "Confirmation No,Resv Status,Arrival,Departure,Source,Guest First Name,Guest Last Name,Guest Email,Membership Number\n"
                + c + "1,CHECKED OUT,01.09.2026,04.09.2026,WEB,Marko,Čukon,,"+ n1 + "\n"          // direct, by card → 3 nights
                + c + "2,CHECKED OUT,01.08.2026,03.08.2026,BOOKING,Marko,Čukon,marko." + run + "@example.com,\n" // OTA
                + c + "3,CHECKED OUT,05.08.2026,06.08.2026,WEB,Nepoznat,Gost,nobody." + run + "@example.com,\n" // no member
                + c + "4,CHECKED OUT,10.08.2026,12.08.2026,WEB,Ana,Horvat,ana." + run + "@example.com,\n"      // no wallet
                + c + "5,RESERVED,01.12.2026,05.12.2026,WEB,Marko,Čukon,,"+ n1 + "\n"              // future
                + c + "6,CHECKED OUT,03.09.2026,06.09.2026,WEB,Marko,Čukon,,"+ n1 + "\n";          // overlaps 03.09.
        LoyaltyImportBatch rb = applyBatch(importAndValidate(program, LoyaltyImportBatch.ImportType.STAYS, hotel, reservations, om.createObjectNode()));
        assertThat(rb.getRowsApplied()).isEqualTo(6);
        drainEvaluations();

        assertThat(reject(c + "2")).isEqualTo("OTA_CHANNEL");
        assertThat(reject(c + "3")).isEqualTo("NO_MATCH");
        assertThat(reject(c + "4")).isEqualTo("NO_WALLET");
        assertThat(reject(c + "5")).isEqualTo("NOT_STAYED");
        assertThat(result(c + "1")).isEqualTo("POSTED");
        assertThat(result(c + "6")).isEqualTo("POSTED");
        // 01,02,03.09 from #1 and 04,05.09 from #6 (03.09 only once)
        assertThat(count("select count(*) from loyalty_ledger where member_id = ? and source = 'STAY' and active", marko.getId())).isEqualTo(5);
        marko = reload(marko);
        assertThat(marko.getNightsTotal()).isEqualTo(78);
        assertThat(marko.getLastStayCheckout()).isEqualTo(LocalDate.of(2026, 9, 6));
        assertThat(marko.getTierValidUntil()).isEqualTo(LocalDate.of(2028, 9, 6));
        assertThat(eventTypes()).contains("loyalty.stay.posted", "loyalty.stay.rejected", "pms.reservation.created");
        // CRM projection counts every checked-out stay, OTA included (#1, #2, #6: 3 + 2 + 3 nights); loyalty does not
        Long markoContact = marko.getContactId();
        assertThat(jdbc.queryForObject("select stays_total from crm_contact where id = ?", Integer.class, markoContact)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select nights_total from crm_contact where id = ?", Integer.class, markoContact)).isEqualTo(8);
        assertThat(jdbc.queryForObject("select next_arrival from crm_contact where id = ?", LocalDate.class, markoContact)).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(jdbc.queryForObject("select kind from crm_contact where id = ?", String.class, markoContact)).isEqualTo("GUEST");

        // --- PMS profiles: NAME_ID lands on the existing person (by e-mail), cards and consents follow ------------
        String profiles = "NAME_ID;FIRST_NAME;LAST_NAME;EMAIL;NATIONALITY;TOTAL_ARRIVALS;TOTAL_NIGHTS;ROOM_REVENUE_YTD;LAST_STAY;MEMBER_CARD_TYPE_1;MEMBER_CARD_NO_1;MEMBER_LEVEL_1;NEWSLETTER\n"
                + "P" + run + "1;Ana;Horvat;ana." + run + "@example.com;HR;4;9;850,00;2025-01-15;RADISSON;RR" + run + ";GOLD;Y\n"
                + "P" + run + "2;Novi;Gost;novi." + run + "@example.com;DE;1;2;300,00;2024-05-05;;;;N\n";
        LoyaltyImportBatch pb = applyBatch(importAndValidate(program, LoyaltyImportBatch.ImportType.PROFILES, null, profiles, om.createObjectNode().put("asOf", "2026-01-01")));
        assertThat(pb.getRowsApplied()).isEqualTo(2);
        Long anaContact = ana.getContactId();
        assertThat(jdbc.queryForObject("select opera_profile_id from crm_contact where id = ?", String.class, anaContact)).isEqualTo("P" + run + "1");
        assertThat(count("select count(*) from crm_contact_membership where contact_id = ? and program_code = 'RADISSON'", anaContact)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select granted from crm_contact_consent where contact_id = ? and channel = 'EMAIL'", Boolean.class, anaContact)).isTrue();
        assertThat(jdbc.queryForObject("select pms_opening_nights from crm_contact where id = ?", Integer.class, anaContact)).isEqualTo(9);
        assertThat(count("select count(*) from crm_contact where tenant_id = ? and opera_profile_id = 'P" + run + "2' and kind = 'GUEST' and source = 'OPERA'", org)).isEqualTo(1);
        assertThat(count("select count(*) from event_delivery d join integration_event e on e.id = d.event_id where e.tenant_id = ? and d.status = 'PENDING'", org))
                .as("deliveries queued for the subscription").isGreaterThan(0);

        // re-import of an unchanged export queues nothing
        long evals = count("select count(*) from loyalty_reservation_evaluation where tenant_id = ?", org);
        applyBatch(importAndValidate(program, LoyaltyImportBatch.ImportType.STAYS, hotel, reservations, om.createObjectNode()));
        assertThat(count("select count(*) from loyalty_reservation_evaluation where tenant_id = ?", org)).isEqualTo(evals);

        // storno after posting reverses only that reservation's nights; #6 keeps 04-05.09
        String cancelled = reservations.replace(c + "1,CHECKED OUT", c + "1,CANCELLED");
        applyBatch(importAndValidate(program, LoyaltyImportBatch.ImportType.STAYS, hotel, cancelled, om.createObjectNode()));
        drainEvaluations();
        assertThat(result(c + "1")).isEqualTo("REVERSED");
        assertThat(count("select count(*) from loyalty_ledger where member_id = ? and source = 'STAY' and active", marko.getId())).isEqualTo(2);
        assertThat(reload(marko).getNightsTotal()).isEqualTo(75);
        assertThat(eventTypes()).contains("loyalty.stay.reversed");

        // a re-evaluation of #6 now picks up 03.09 (freed by the storno)
        Long r6 = jdbc.queryForObject("select id from pms_reservation where confirmation_no = ?", Long.class, c + "6");
        tx.executeWithoutResult(s -> jdbc.update("insert into loyalty_reservation_evaluation (tenant_id, reservation_id, result) values (?, ?, 'PENDING')", org, r6));
        drainEvaluations();
        assertThat(count("select count(*) from loyalty_ledger where member_id = ? and source = 'STAY' and active", marko.getId())).isEqualTo(3);

        // ana gets a wallet → +2 bonus once; her stay is re-evaluated and now qualifies
        memberService.markWallet(org, ana.getId(), null, null);
        memberService.markWallet(org, ana.getId(), null, null);
        assertThat(count("select count(*) from loyalty_ledger where member_id = ? and source = 'WALLET'", ana.getId())).isEqualTo(1);
        Long r4 = jdbc.queryForObject("select id from pms_reservation where confirmation_no = ?", Long.class, c + "4");
        tx.executeWithoutResult(s -> jdbc.update("insert into loyalty_reservation_evaluation (tenant_id, reservation_id, result) values (?, ?, 'PENDING')", org, r4));
        drainEvaluations();
        assertThat(reload(ana).getNightsTotal()).isEqualTo(12 + 2 + 2);

        // manual correction is audited and can only be undone by a counter-entry
        Long operator = jdbc.queryForObject("select id from app_user order by id limit 1", Long.class);
        var entries = ledgerService.adjust(org, ana.getId(), new LoyaltyLedgerService.Adjustment(
                LoyaltyLedgerService.AdjustmentType.ADD_NIGHTS, 15, null, null, "missing nights claim", null, "OPERA 123", false), operator);
        assertThat(tierCode(reload(ana))).isEqualTo("GOLD");
        ledgerService.reverse(org, entries.get(0).getId(), "claim was a duplicate", operator);
        assertThat(tierCode(reload(ana))).isEqualTo("SILVER");
        assertThatThrownBy(() -> jdbc.update("update loyalty_ledger set qty = 99 where id = ?", entries.get(0).getId())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from loyalty_ledger where id = ?", entries.get(0).getId())).hasMessageContaining("append-only");

        // status expiry: far in the future everything lapses back to the entry tier
        expiryJob.run(LocalDate.of(2031, 1, 1));
        assertThat(tierCode(reload(marko))).isEqualTo("LIGHT");
        assertThat(eventTypes()).contains("loyalty.tier.downgraded");
    }

    @Test
    void onlyOneInstanceClaimsABatch() {
        LoyaltyProgram program = arenaProgram();
        files.put("it/" + org + "/claim.csv", "a\n1\n");
        LoyaltyImportBatch b = tx.execute(s -> {
            LoyaltyImportBatch batch = importService.create(org, program.getId(), LoyaltyImportBatch.ImportType.MEMBERS, null, "c.csv", 4L, null).batch();
            batch.setStatus(LoyaltyImportBatch.Status.QUEUED);
            return batchRepo.save(batch);
        });
        List<Long> first = tx.execute(s -> batchRepo.claimNext("instance-a"));
        List<Long> second = tx.execute(s -> batchRepo.claimNext("instance-b"));
        assertThat(first).contains(b.getId());
        assertThat(second).doesNotContain(b.getId());
        // a dead instance's lease expires and the batch is claimable again
        tx.executeWithoutResult(s -> jdbc.update("update loyalty_import_batch set lease_until = now() - interval '1 second' where id = ?", b.getId()));
        List<Long> third = tx.execute(s -> batchRepo.claimNext("instance-b"));
        assertThat(third).contains(b.getId());
        tx.executeWithoutResult(s -> jdbc.update("update loyalty_import_batch set status = 'CANCELLED', lease_until = null where id = ?", b.getId()));
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private LoyaltyProgram arenaProgram() {
        LoyaltyProgram p = programService.create(org, LoyaltyProgram.builder().code("IT_" + run).name("Arena Rewards IT " + run).build());
        ObjectNode params = om.createObjectNode().put("validity_months", 24).put("wallet_bonus_nights", 2).put("unknown_code_policy", "QUALIFYING");
        var draft = programService.createDraft(org, p.getId(), LocalDate.of(2019, 1, 1), LoyaltyRuleSet.TierPolicy.ROLLING_VALIDITY, params, "Arena 2026", null);
        programService.updateDraft(org, draft.ruleSet().getId(), null, null, null, null,
                List.of(new LoyaltyProgramService.TierInput("LIGHT", "Light", 0, 0, null),
                        new LoyaltyProgramService.TierInput("SILVER", "Silver", 1, 10, null),
                        new LoyaltyProgramService.TierInput("GOLD", "Gold", 2, 31, null),
                        new LoyaltyProgramService.TierInput("PLATINUM", "Platinum", 3, 61, null)),
                List.of(step(1, RuleCode.MEMBER_MATCH, "NO_MATCH"), step(2, RuleCode.CHANNEL, "OTA_CHANNEL"),
                        step(3, RuleCode.HOLDER, "NOT_HOLDER"), step(4, RuleCode.ACTIVATION, "PRE_ACTIVATION"),
                        step(5, RuleCode.WALLET, "NO_WALLET"), step(6, RuleCode.STAY_COMPLETED, "NOT_STAYED"),
                        step(7, RuleCode.EXCLUSIVITY_FLAG, "RADISSON"), step(8, RuleCode.UNIQUE_PER_DATE, "DUPLICATE")));
        programService.activate(org, draft.ruleSet().getId());
        programService.replaceChannelMap(org, p.getId(), List.of(
                new LoyaltyProgramService.ChannelInput(null, LoyaltyChannelMap.CodeType.SOURCE, "WEB", true),
                new LoyaltyProgramService.ChannelInput(null, LoyaltyChannelMap.CodeType.SOURCE, "BOOKING", false)));
        return p;
    }

    private static LoyaltyProgramService.StepInput step(int order, RuleCode code, String reject) {
        return new LoyaltyProgramService.StepInput(order, code, true, null, reject);
    }

    private LoyaltyImportBatch importAndValidate(LoyaltyProgram program, LoyaltyImportBatch.ImportType type, Long property, String csv, ObjectNode options) {
        LoyaltyImportBatch b = importService.create(org, program.getId(), type, property, "f.csv", (long) csv.length(), null).batch();
        files.put(b.getS3Key(), csv);
        if (type == LoyaltyImportBatch.ImportType.MEMBERS && !options.has("asOf")) {
            options.put("asOf", "2026-10-01");
        }
        importService.start(org, b.getId(), null, options);
        importWorker.drain();
        return batchRepo.findById(b.getId()).orElseThrow();
    }

    private LoyaltyImportBatch applyBatch(LoyaltyImportBatch b) {
        importService.apply(org, b.getId());
        importWorker.drain();
        LoyaltyImportBatch done = batchRepo.findById(b.getId()).orElseThrow();
        if (done.getImportType() == LoyaltyImportBatch.ImportType.MEMBERS) {
            // imported "today" means active from today; backdate so the 2026 test stays are not pre-activation
            jdbc.update("update loyalty_member set activated_at = '2026-01-01T00:00:00+01' where program_id = ? and source = 'IMPORT'", done.getProgramId());
        }
        return done;
    }

    private void drainEvaluations() {
        while (evaluationWorker.drainOnce(100) > 0) {
            // keep draining
        }
    }

    private String reject(String conf) {
        return jdbc.queryForObject("""
                select e.reject_code from loyalty_reservation_evaluation e join pms_reservation r on r.id = e.reservation_id
                where r.confirmation_no = ? and e.result <> 'PENDING' order by e.id desc limit 1""", String.class, conf);
    }

    private String result(String conf) {
        return jdbc.queryForObject("""
                select e.result from loyalty_reservation_evaluation e join pms_reservation r on r.id = e.reservation_id
                where r.confirmation_no = ? and e.result not in ('PENDING','SKIPPED') order by e.id desc limit 1""", String.class, conf);
    }

    private List<String> eventTypes() {
        return jdbc.queryForList("select distinct type from integration_event where tenant_id = ?", String.class, org);
    }

    private long count(String sql, Object arg) {
        Long n = jdbc.queryForObject(sql, Long.class, arg);
        return n == null ? 0 : n;
    }

    private LoyaltyMember reload(LoyaltyMember m) {
        return memberRepo.findById(m.getId()).orElseThrow();
    }

    private String tierCode(LoyaltyMember m) {
        return jdbc.queryForObject("select code from loyalty_tier where id = ?", String.class, m.getAssignedTierId());
    }
}
