package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AvailabilityCancellationPolicyDto {
    private Long policyId;
    private String cancellationPolicyText;
    private LocalDateTime cancellationFreeUntil;
}
