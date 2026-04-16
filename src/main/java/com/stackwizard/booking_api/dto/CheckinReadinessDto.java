package com.stackwizard.booking_api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckinReadinessDto {
    /** True when POST check-in is expected to succeed (no blocking issues). */
    private boolean eligible;
    private List<String> issues;
}
