package com.stackwizard.booking_api.dto;

import lombok.Data;

@Data
public class CrmStageChangeRequest {
    private Long stageId;
    private Long outcomeReasonId;
    private String outcomeNote;
    private String note;
}
