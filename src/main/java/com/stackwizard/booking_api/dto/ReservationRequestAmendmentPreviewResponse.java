package com.stackwizard.booking_api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationRequestAmendmentPreviewResponse {
    private boolean ok;
    @Builder.Default
    private List<String> messages = new ArrayList<>();
    /** Sum(new gross) - sum(old gross) for replaced lines; zero for pure resource swap with copied amounts. */
    private BigDecimal grossDelta;
}
