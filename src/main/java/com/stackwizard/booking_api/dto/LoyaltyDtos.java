package com.stackwizard.booking_api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.model.LoyaltyImportBatch;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.service.loyalty.LoyaltyProgramService;

import java.time.LocalDate;
import java.util.List;

public final class LoyaltyDtos {
    private LoyaltyDtos() {
    }

    public record RuleSetDraftRequest(LocalDate effectiveFrom, LoyaltyRuleSet.TierPolicy tierPolicy, JsonNode params,
                                      String note, Long copyFromId) {
    }

    public record RuleSetUpdateRequest(LocalDate effectiveFrom, LoyaltyRuleSet.TierPolicy tierPolicy, JsonNode params, String note,
                                       List<LoyaltyProgramService.TierInput> tiers,
                                       List<LoyaltyProgramService.StepInput> steps) {
    }

    public record ReasonRequest(String reason) {
    }

    public record WalletRequest(String exceptionReason) {
    }

    public record EmailRequest(String email) {
    }

    public record MatchRequest(Long contactId) {
    }

    public record MergeRequest(Long fromContactId, String reason) {
    }

    public record ImportCreateRequest(Long programId, LoyaltyImportBatch.ImportType importType, Long propertyTenantId,
                                      String fileName, Long fileSize) {
    }

    public record ImportStartRequest(JsonNode columnMapping, JsonNode options) {
    }

    public record SubscriptionRequest(String name, String endpointUrl, List<String> eventTypes, Boolean active) {
    }
}
