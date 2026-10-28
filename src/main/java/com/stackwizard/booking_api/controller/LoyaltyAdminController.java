package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.LoyaltyDtos;
import com.stackwizard.booking_api.model.LoyaltyChannelMap;
import com.stackwizard.booking_api.model.LoyaltyLedgerEntry;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.LoyaltyMemberIdentifier;
import com.stackwizard.booking_api.model.LoyaltyProgram;
import com.stackwizard.booking_api.model.LoyaltyReservation;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.model.LoyaltyTierHistory;
import com.stackwizard.booking_api.security.LoyaltyAccess;
import com.stackwizard.booking_api.security.LoyaltyPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.loyalty.LoyaltyLedgerService;
import com.stackwizard.booking_api.service.loyalty.LoyaltyMemberService;
import com.stackwizard.booking_api.service.loyalty.LoyaltyProgramService;
import com.stackwizard.booking_api.service.loyalty.LoyaltyReservationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Loyalty back office (booking-admin): program setup, members, corrections, reservations / match queue. */
@RestController
@RequestMapping("/api/loyalty")
public class LoyaltyAdminController {
    private final LoyaltyProgramService programService;
    private final LoyaltyMemberService memberService;
    private final LoyaltyLedgerService ledgerService;
    private final LoyaltyReservationService reservationService;
    private final LoyaltyAccess access;

    public LoyaltyAdminController(LoyaltyProgramService programService,
                                  LoyaltyMemberService memberService,
                                  LoyaltyLedgerService ledgerService,
                                  LoyaltyReservationService reservationService,
                                  LoyaltyAccess access) {
        this.programService = programService;
        this.memberService = memberService;
        this.ledgerService = ledgerService;
        this.reservationService = reservationService;
        this.access = access;
    }

    // --- program / rules ------------------------------------------------------------------------------------

    @GetMapping("/programs")
    public List<LoyaltyProgram> programs() {
        access.require(LoyaltyPermission.READ);
        return programService.list(TenantResolver.requireOrgTenantId());
    }

    @PostMapping("/programs")
    public LoyaltyProgram createProgram(@RequestBody LoyaltyProgram body) {
        access.require(LoyaltyPermission.CONFIG);
        return programService.create(TenantResolver.requireOrgTenantId(), body);
    }

    @PutMapping("/programs/{programId}")
    public LoyaltyProgram updateProgram(@PathVariable Long programId, @RequestBody LoyaltyProgram body) {
        access.require(LoyaltyPermission.CONFIG);
        return programService.update(TenantResolver.requireOrgTenantId(), programId, body);
    }

    @GetMapping("/programs/{programId}/rule-sets")
    public List<LoyaltyRuleSet> ruleSets(@PathVariable Long programId) {
        access.require(LoyaltyPermission.READ);
        return programService.ruleSets(TenantResolver.requireOrgTenantId(), programId);
    }

    @PostMapping("/programs/{programId}/rule-sets")
    public LoyaltyProgramService.RuleSetView createRuleSet(@PathVariable Long programId, @RequestBody LoyaltyDtos.RuleSetDraftRequest body) {
        access.require(LoyaltyPermission.CONFIG);
        return programService.createDraft(TenantResolver.requireOrgTenantId(), programId, body.effectiveFrom(), body.tierPolicy(),
                body.params(), body.note(), body.copyFromId());
    }

    @GetMapping("/rule-sets/{ruleSetId}")
    public LoyaltyProgramService.RuleSetView ruleSet(@PathVariable Long ruleSetId) {
        access.require(LoyaltyPermission.READ);
        return programService.view(TenantResolver.requireOrgTenantId(), ruleSetId);
    }

    @PutMapping("/rule-sets/{ruleSetId}")
    public LoyaltyProgramService.RuleSetView updateRuleSet(@PathVariable Long ruleSetId, @RequestBody LoyaltyDtos.RuleSetUpdateRequest body) {
        access.require(LoyaltyPermission.CONFIG);
        return programService.updateDraft(TenantResolver.requireOrgTenantId(), ruleSetId, body.effectiveFrom(), body.tierPolicy(),
                body.params(), body.note(), body.tiers(), body.steps());
    }

    @PostMapping("/rule-sets/{ruleSetId}/activate")
    public LoyaltyProgramService.RuleSetView activateRuleSet(@PathVariable Long ruleSetId) {
        access.require(LoyaltyPermission.CONFIG);
        return programService.activate(TenantResolver.requireOrgTenantId(), ruleSetId);
    }

    @GetMapping("/programs/{programId}/channel-map")
    public List<LoyaltyChannelMap> channelMap(@PathVariable Long programId) {
        access.require(LoyaltyPermission.READ);
        return programService.channelMap(TenantResolver.requireOrgTenantId(), programId);
    }

    @PutMapping("/programs/{programId}/channel-map")
    public List<LoyaltyChannelMap> replaceChannelMap(@PathVariable Long programId, @RequestBody List<LoyaltyProgramService.ChannelInput> body) {
        access.require(LoyaltyPermission.CONFIG);
        return programService.replaceChannelMap(TenantResolver.requireOrgTenantId(), programId, body);
    }

    // --- members --------------------------------------------------------------------------------------------

    @GetMapping("/programs/{programId}/members")
    public Page<LoyaltyMember> members(@PathVariable Long programId,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) String email,
                                       @RequestParam(required = false) String number,
                                       @RequestParam(required = false) Long tierId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        access.require(LoyaltyPermission.READ);
        return memberService.search(TenantResolver.requireOrgTenantId(), programId, q, email, number, tierId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
    }

    @PostMapping("/programs/{programId}/members")
    public LoyaltyMemberService.MemberView enrol(@PathVariable Long programId, @RequestBody LoyaltyMemberService.MemberInput body) {
        access.require(LoyaltyPermission.ADJUST);
        Long tenantId = TenantResolver.requireOrgTenantId();
        LoyaltyMember m = memberService.enrol(tenantId, programId, body, LoyaltyMember.Source.ADMIN);
        return memberService.view(tenantId, m.getId());
    }

    @GetMapping("/members/{memberId}")
    public LoyaltyMemberService.MemberView member(@PathVariable Long memberId) {
        access.require(LoyaltyPermission.READ);
        return memberService.view(TenantResolver.requireOrgTenantId(), memberId);
    }

    @PatchMapping("/members/{memberId}")
    public LoyaltyMemberService.MemberView updateMember(@PathVariable Long memberId, @RequestBody LoyaltyMemberService.MemberInput body) {
        access.require(LoyaltyPermission.ADJUST);
        return memberService.update(TenantResolver.requireOrgTenantId(), memberId, body);
    }

    @PostMapping("/members/{memberId}/emails")
    public LoyaltyMemberIdentifier addEmail(@PathVariable Long memberId, @RequestBody LoyaltyDtos.EmailRequest body) {
        access.require(LoyaltyPermission.ADJUST);
        return memberService.addSecondaryEmail(TenantResolver.requireOrgTenantId(), memberId, body.email());
    }

    @DeleteMapping("/members/{memberId}/identifiers/{identifierId}")
    public void removeIdentifier(@PathVariable Long memberId, @PathVariable Long identifierId) {
        access.require(LoyaltyPermission.ADJUST);
        memberService.removeIdentifier(TenantResolver.requireOrgTenantId(), memberId, identifierId);
    }

    @PostMapping("/members/{memberId}/wallet")
    public LoyaltyMember wallet(@PathVariable Long memberId, @RequestBody(required = false) LoyaltyDtos.WalletRequest body) {
        access.require(LoyaltyPermission.ADJUST);
        return memberService.markWallet(TenantResolver.requireOrgTenantId(), memberId, body == null ? null : body.exceptionReason(),
                access.currentUserId());
    }

    @PostMapping("/members/{memberId}/deactivate")
    public LoyaltyMember deactivate(@PathVariable Long memberId, @RequestBody(required = false) LoyaltyDtos.ReasonRequest body) {
        access.require(LoyaltyPermission.ADJUST);
        return memberService.deactivate(TenantResolver.requireOrgTenantId(), memberId, body == null ? null : body.reason());
    }

    @PostMapping("/members/{memberId}/anonymize")
    public LoyaltyMember anonymize(@PathVariable Long memberId) {
        access.require(LoyaltyPermission.CONFIG);
        return memberService.anonymize(TenantResolver.requireOrgTenantId(), memberId);
    }

    @GetMapping("/members/{memberId}/ledger")
    public List<LoyaltyLedgerEntry> ledger(@PathVariable Long memberId) {
        access.require(LoyaltyPermission.READ);
        return memberService.ledger(TenantResolver.requireOrgTenantId(), memberId);
    }

    @GetMapping("/members/{memberId}/tier-history")
    public List<LoyaltyTierHistory> tierHistory(@PathVariable Long memberId) {
        access.require(LoyaltyPermission.READ);
        return memberService.tierHistory(TenantResolver.requireOrgTenantId(), memberId);
    }

    @GetMapping("/members/{memberId}/reservations")
    public List<LoyaltyReservation> memberReservations(@PathVariable Long memberId) {
        access.require(LoyaltyPermission.READ);
        return reservationService.forMember(TenantResolver.requireOrgTenantId(), memberId);
    }

    @PostMapping("/members/{memberId}/adjustments")
    public List<LoyaltyLedgerEntry> adjust(@PathVariable Long memberId, @RequestBody LoyaltyLedgerService.Adjustment body) {
        access.require(LoyaltyPermission.ADJUST);
        return ledgerService.adjust(TenantResolver.requireOrgTenantId(), memberId, body, access.currentUserId());
    }

    @PostMapping("/ledger/{entryId}/reverse")
    public LoyaltyLedgerEntry reverse(@PathVariable Long entryId, @RequestBody LoyaltyDtos.ReasonRequest body) {
        access.require(LoyaltyPermission.ADJUST);
        return ledgerService.reverse(TenantResolver.requireOrgTenantId(), entryId, body == null ? null : body.reason(), access.currentUserId());
    }

    // --- reservations / match queue -------------------------------------------------------------------------

    @GetMapping("/reservations")
    public Page<LoyaltyReservation> reservations(@RequestParam(required = false) LoyaltyReservation.Status status,
                                                 @RequestParam(required = false) Long propertyTenantId,
                                                 @RequestParam(defaultValue = "false") boolean unmatched,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "50") int size) {
        access.require(LoyaltyPermission.READ);
        return reservationService.search(TenantResolver.requireOrgTenantId(), status, propertyTenantId, unmatched,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
    }

    @GetMapping("/reservations/{reservationId}")
    public LoyaltyReservationService.ReservationView reservation(@PathVariable Long reservationId) {
        access.require(LoyaltyPermission.READ);
        return reservationService.view(TenantResolver.requireOrgTenantId(), reservationId);
    }

    @PostMapping("/reservations/{reservationId}/match")
    public LoyaltyReservationService.ReservationView match(@PathVariable Long reservationId, @RequestBody LoyaltyDtos.MatchRequest body) {
        access.require(LoyaltyPermission.ADJUST);
        return reservationService.matchManually(TenantResolver.requireOrgTenantId(), reservationId, body.memberId());
    }

    @PostMapping("/reservations/{reservationId}/reevaluate")
    public LoyaltyReservationService.ReservationView reevaluate(@PathVariable Long reservationId) {
        access.require(LoyaltyPermission.ADJUST);
        return reservationService.reevaluate(TenantResolver.requireOrgTenantId(), reservationId);
    }
}
