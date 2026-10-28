package com.stackwizard.booking_api.service.loyalty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.model.LoyaltyChannelMap;
import com.stackwizard.booking_api.model.LoyaltyProgram;
import com.stackwizard.booking_api.model.LoyaltyQualificationStep;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.model.LoyaltyTier;
import com.stackwizard.booking_api.repository.LoyaltyChannelMapRepository;
import com.stackwizard.booking_api.repository.LoyaltyProgramRepository;
import com.stackwizard.booking_api.repository.LoyaltyQualificationStepRepository;
import com.stackwizard.booking_api.repository.LoyaltyRuleSetRepository;
import com.stackwizard.booking_api.repository.LoyaltyTierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Program configuration. Rule sets are versioned: a DRAFT is edited freely, activation closes the previous ACTIVE
 * version the day the new one starts, and an ACTIVE version is never edited (old stays keep their rules, FR-10.1).
 */
@Service
public class LoyaltyProgramService {
    private final LoyaltyProgramRepository programRepo;
    private final LoyaltyRuleSetRepository ruleSetRepo;
    private final LoyaltyTierRepository tierRepo;
    private final LoyaltyQualificationStepRepository stepRepo;
    private final LoyaltyChannelMapRepository channelMapRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LoyaltyProgramService(LoyaltyProgramRepository programRepo,
                                 LoyaltyRuleSetRepository ruleSetRepo,
                                 LoyaltyTierRepository tierRepo,
                                 LoyaltyQualificationStepRepository stepRepo,
                                 LoyaltyChannelMapRepository channelMapRepo) {
        this.programRepo = programRepo;
        this.ruleSetRepo = ruleSetRepo;
        this.tierRepo = tierRepo;
        this.stepRepo = stepRepo;
        this.channelMapRepo = channelMapRepo;
    }

    public record TierInput(String code, String name, Integer rank, Integer threshold, JsonNode display) {
    }

    public record StepInput(Integer stepOrder, LoyaltyQualificationStep.RuleCode ruleCode, Boolean enabled, JsonNode params, String rejectCode) {
    }

    public record ChannelInput(Long propertyTenantId, LoyaltyChannelMap.CodeType codeType, String code, Boolean qualifying) {
    }

    public record RuleSetView(LoyaltyRuleSet ruleSet, List<LoyaltyTier> tiers, List<LoyaltyQualificationStep> steps) {
    }

    // --- programs -------------------------------------------------------------------------------------------

    public List<LoyaltyProgram> list(Long tenantId) {
        return programRepo.findByTenantIdOrderByIdAsc(tenantId);
    }

    public LoyaltyProgram require(Long tenantId, Long programId) {
        return programRepo.findByIdAndTenantId(programId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Loyalty program not found"));
    }

    @Transactional
    public LoyaltyProgram create(Long tenantId, LoyaltyProgram input) {
        if (!StringUtils.hasText(input.getCode()) || !StringUtils.hasText(input.getName())) {
            throw new IllegalArgumentException("code and name are required");
        }
        LoyaltyProgram program = LoyaltyProgram.builder()
                .tenantId(tenantId)
                .code(input.getCode().trim().toUpperCase(Locale.ROOT))
                .name(input.getName().trim())
                .accrualUnit(input.getAccrualUnit() == null ? LoyaltyProgram.AccrualUnit.NIGHT : input.getAccrualUnit())
                .memberNumberPrefix(trimToNull(input.getMemberNumberPrefix()))
                .memberNumberNext(input.getMemberNumberNext() == null ? 1L : input.getMemberNumberNext())
                .anonymousPricing(input.getAnonymousPricing() == null ? LoyaltyProgram.AnonymousPricing.NONE : input.getAnonymousPricing())
                .active(input.getActive() == null || input.getActive())
                .updatedAt(OffsetDateTime.now())
                .build();
        return programRepo.save(program);
    }

    @Transactional
    public LoyaltyProgram update(Long tenantId, Long programId, LoyaltyProgram input) {
        LoyaltyProgram program = require(tenantId, programId);
        if (StringUtils.hasText(input.getName())) {
            program.setName(input.getName().trim());
        }
        if (input.getMemberNumberPrefix() != null) {
            program.setMemberNumberPrefix(trimToNull(input.getMemberNumberPrefix()));
        }
        if (input.getMemberNumberNext() != null) {
            if (input.getMemberNumberNext() < program.getMemberNumberNext()) {
                throw new IllegalArgumentException("memberNumberNext can only move forward");
            }
            program.setMemberNumberNext(input.getMemberNumberNext());
        }
        if (input.getAnonymousPricing() != null) {
            program.setAnonymousPricing(input.getAnonymousPricing());
        }
        if (input.getActive() != null) {
            program.setActive(input.getActive());
        }
        program.setUpdatedAt(OffsetDateTime.now());
        return programRepo.save(program);
    }

    // --- rule sets ------------------------------------------------------------------------------------------

    public List<LoyaltyRuleSet> ruleSets(Long tenantId, Long programId) {
        require(tenantId, programId);
        return ruleSetRepo.findByProgramIdOrderByVersionDesc(programId);
    }

    public RuleSetView view(Long tenantId, Long ruleSetId) {
        LoyaltyRuleSet rs = requireRuleSet(tenantId, ruleSetId);
        return new RuleSetView(rs, tierRepo.findByRuleSetIdOrderByRankAsc(rs.getId()), stepRepo.findByRuleSetIdOrderByStepOrderAsc(rs.getId()));
    }

    /** Rule set valid on a date; stays are always evaluated with the rules of their own date. */
    public LoyaltyRuleSet ruleSetOn(Long programId, LocalDate date) {
        return ruleSetRepo.findActiveOn(programId, date)
                .or(() -> ruleSetRepo.findActive(programId).stream().reduce((first, second) -> second)) // earliest ACTIVE
                .orElseThrow(() -> new IllegalStateException("Loyalty program " + programId + " has no active rule set"));
    }

    /** New DRAFT; when {@code copyFromId} is given, tiers and steps are copied so a version bump is one call. */
    @Transactional
    public RuleSetView createDraft(Long tenantId, Long programId, LocalDate effectiveFrom, LoyaltyRuleSet.TierPolicy policy,
                                   JsonNode params, String note, Long copyFromId) {
        require(tenantId, programId);
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("effectiveFrom is required");
        }
        LoyaltyRuleSet source = copyFromId == null ? null : requireRuleSet(tenantId, copyFromId);
        LoyaltyRuleSet rs = ruleSetRepo.save(LoyaltyRuleSet.builder()
                .tenantId(tenantId)
                .programId(programId)
                .version(ruleSetRepo.maxVersion(programId) + 1)
                .effectiveFrom(effectiveFrom)
                .tierPolicy(policy != null ? policy : source != null ? source.getTierPolicy() : LoyaltyRuleSet.TierPolicy.ROLLING_VALIDITY)
                .params(params != null ? params : source != null ? source.getParams().deepCopy() : objectMapper.createObjectNode())
                .status(LoyaltyRuleSet.Status.DRAFT)
                .note(trimToNull(note))
                .build());
        if (source != null) {
            for (LoyaltyTier t : tierRepo.findByRuleSetIdOrderByRankAsc(source.getId())) {
                tierRepo.save(LoyaltyTier.builder().tenantId(tenantId).ruleSetId(rs.getId()).code(t.getCode()).name(t.getName())
                        .rank(t.getRank()).threshold(t.getThreshold()).display(t.getDisplay().deepCopy()).build());
            }
            for (LoyaltyQualificationStep s : stepRepo.findByRuleSetIdOrderByStepOrderAsc(source.getId())) {
                stepRepo.save(LoyaltyQualificationStep.builder().tenantId(tenantId).ruleSetId(rs.getId()).stepOrder(s.getStepOrder())
                        .ruleCode(s.getRuleCode()).enabled(s.getEnabled()).params(s.getParams().deepCopy()).rejectCode(s.getRejectCode()).build());
            }
        }
        return view(tenantId, rs.getId());
    }

    @Transactional
    public RuleSetView updateDraft(Long tenantId, Long ruleSetId, LocalDate effectiveFrom, LoyaltyRuleSet.TierPolicy policy,
                                   JsonNode params, String note, List<TierInput> tiers, List<StepInput> steps) {
        LoyaltyRuleSet rs = requireDraft(tenantId, ruleSetId);
        if (effectiveFrom != null) {
            rs.setEffectiveFrom(effectiveFrom);
        }
        if (policy != null) {
            rs.setTierPolicy(policy);
        }
        if (params != null) {
            rs.setParams(params);
        }
        if (note != null) {
            rs.setNote(trimToNull(note));
        }
        ruleSetRepo.save(rs);
        if (tiers != null) {
            replaceTiers(rs, tiers);
        }
        if (steps != null) {
            replaceSteps(rs, steps);
        }
        return view(tenantId, rs.getId());
    }

    @Transactional
    public RuleSetView activate(Long tenantId, Long ruleSetId) {
        LoyaltyRuleSet rs = requireDraft(tenantId, ruleSetId);
        List<LoyaltyTier> tiers = tierRepo.findByRuleSetIdOrderByRankAsc(rs.getId());
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("A rule set needs at least one tier");
        }
        for (LoyaltyRuleSet other : ruleSetRepo.findActive(rs.getProgramId())) {
            if (!other.getEffectiveFrom().isBefore(rs.getEffectiveFrom())) {
                throw new IllegalStateException("Version " + other.getVersion() + " already starts on or after " + rs.getEffectiveFrom());
            }
            if (other.getEffectiveTo() == null || other.getEffectiveTo().isAfter(rs.getEffectiveFrom())) {
                other.setEffectiveTo(rs.getEffectiveFrom());
                ruleSetRepo.saveAndFlush(other);
            }
        }
        rs.setStatus(LoyaltyRuleSet.Status.ACTIVE);
        ruleSetRepo.save(rs);
        return view(tenantId, rs.getId());
    }

    private void replaceTiers(LoyaltyRuleSet rs, List<TierInput> inputs) {
        Set<String> codes = new HashSet<>();
        Set<Integer> ranks = new HashSet<>();
        List<LoyaltyTier> out = new ArrayList<>();
        for (TierInput in : inputs) {
            if (!StringUtils.hasText(in.code()) || !StringUtils.hasText(in.name()) || in.rank() == null || in.threshold() == null) {
                throw new IllegalArgumentException("Each tier needs code, name, rank and threshold");
            }
            if (!codes.add(in.code().trim().toUpperCase(Locale.ROOT)) || !ranks.add(in.rank())) {
                throw new IllegalArgumentException("Tier codes and ranks must be unique");
            }
            out.add(LoyaltyTier.builder().tenantId(rs.getTenantId()).ruleSetId(rs.getId())
                    .code(in.code().trim().toUpperCase(Locale.ROOT)).name(in.name().trim()).rank(in.rank()).threshold(in.threshold())
                    .display(in.display() != null ? in.display() : objectMapper.createObjectNode()).build());
        }
        List<LoyaltyTier> byRank = out.stream().sorted((a, b) -> a.getRank() - b.getRank()).toList();
        for (int i = 1; i < byRank.size(); i++) {
            if (byRank.get(i).getThreshold() <= byRank.get(i - 1).getThreshold()) {
                throw new IllegalArgumentException("Tier thresholds must grow with rank");
            }
        }
        tierRepo.deleteByRuleSetId(rs.getId());
        tierRepo.flush();
        tierRepo.saveAll(out);
    }

    private void replaceSteps(LoyaltyRuleSet rs, List<StepInput> inputs) {
        List<LoyaltyQualificationStep> out = new ArrayList<>();
        Set<Integer> orders = new HashSet<>();
        Set<LoyaltyQualificationStep.RuleCode> rules = new HashSet<>();
        for (StepInput in : inputs) {
            if (in.stepOrder() == null || in.ruleCode() == null || !StringUtils.hasText(in.rejectCode())) {
                throw new IllegalArgumentException("Each step needs stepOrder, ruleCode and rejectCode");
            }
            if (!orders.add(in.stepOrder()) || !rules.add(in.ruleCode())) {
                throw new IllegalArgumentException("Step orders and rules must be unique");
            }
            out.add(LoyaltyQualificationStep.builder().tenantId(rs.getTenantId()).ruleSetId(rs.getId()).stepOrder(in.stepOrder())
                    .ruleCode(in.ruleCode()).enabled(in.enabled() == null || in.enabled())
                    .params(in.params() != null ? in.params() : objectMapper.createObjectNode())
                    .rejectCode(in.rejectCode().trim().toUpperCase(Locale.ROOT)).build());
        }
        stepRepo.deleteByRuleSetId(rs.getId());
        stepRepo.flush();
        stepRepo.saveAll(out);
    }

    // --- channel map ----------------------------------------------------------------------------------------

    public List<LoyaltyChannelMap> channelMap(Long tenantId, Long programId) {
        require(tenantId, programId);
        return channelMapRepo.findByProgramIdOrderByIdAsc(programId);
    }

    @Transactional
    public List<LoyaltyChannelMap> replaceChannelMap(Long tenantId, Long programId, List<ChannelInput> inputs) {
        require(tenantId, programId);
        Set<String> keys = new HashSet<>();
        List<LoyaltyChannelMap> out = new ArrayList<>();
        for (ChannelInput in : inputs) {
            String code = LoyaltyNormalizer.code(in.code());
            if (code == null || in.codeType() == null || in.qualifying() == null) {
                throw new IllegalArgumentException("Each mapping needs codeType, code and qualifying");
            }
            if (!keys.add(in.propertyTenantId() + "|" + in.codeType() + "|" + code)) {
                throw new IllegalArgumentException("Duplicate mapping for " + in.codeType() + " " + code);
            }
            out.add(LoyaltyChannelMap.builder().tenantId(tenantId).programId(programId).propertyTenantId(in.propertyTenantId())
                    .codeType(in.codeType()).code(code).qualifying(in.qualifying()).build());
        }
        channelMapRepo.deleteByProgramId(programId);
        channelMapRepo.flush();
        return channelMapRepo.saveAll(out);
    }

    private LoyaltyRuleSet requireRuleSet(Long tenantId, Long ruleSetId) {
        return ruleSetRepo.findByIdAndTenantId(ruleSetId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Rule set not found"));
    }

    private LoyaltyRuleSet requireDraft(Long tenantId, Long ruleSetId) {
        LoyaltyRuleSet rs = requireRuleSet(tenantId, ruleSetId);
        if (rs.getStatus() != LoyaltyRuleSet.Status.DRAFT) {
            throw new IllegalStateException("Only a DRAFT rule set can be changed; create a new version instead");
        }
        return rs;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
