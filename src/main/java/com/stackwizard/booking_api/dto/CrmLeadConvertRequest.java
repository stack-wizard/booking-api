package com.stackwizard.booking_api.dto;

import lombok.Data;

@Data
public class CrmLeadConvertRequest {
    private Long accountId;
    private Long contactId;
    private Long pipelineId;
    private String opportunityName;
}
