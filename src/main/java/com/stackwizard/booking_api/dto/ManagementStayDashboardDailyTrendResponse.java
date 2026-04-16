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
public class ManagementStayDashboardDailyTrendResponse {
    private List<ManagementStayDashboardDailyTrendPoint> days;
}
