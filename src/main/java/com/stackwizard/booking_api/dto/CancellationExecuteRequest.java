package com.stackwizard.booking_api.dto;

import lombok.Data;

@Data
public class CancellationExecuteRequest {
    private String settlementMode;
    private String note;
}
