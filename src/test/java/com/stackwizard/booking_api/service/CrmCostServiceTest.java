package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.SalesDtos;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrmCostServiceTest {

    @Test
    void summaryAddsOpportunityCostsAndComputesMarginPercent() {
        List<SalesDtos.EventProfit> events = List.of(
                new SalesDtos.EventProfit(1L, "Day 1", "DEFINITE", new BigDecimal("1000.00"), new BigDecimal("1000.00"),
                        new BigDecimal("300.00"), new BigDecimal("100.00"), new BigDecimal("600.00")),
                new SalesDtos.EventProfit(2L, "Day 2", "TENTATIVE", new BigDecimal("500.00"), null,
                        new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("400.00")));

        SalesDtos.Profitability summary = CrmCostService.summary("EUR", events, new BigDecimal("50"));

        assertThat(summary.revenue()).isEqualByComparingTo("1500.00");
        assertThat(summary.quotedTotal()).isEqualByComparingTo("1000.00");
        assertThat(summary.itemCost()).isEqualByComparingTo("400.00");
        assertThat(summary.extraCost()).isEqualByComparingTo("150.00");
        assertThat(summary.margin()).isEqualByComparingTo("950.00");
        assertThat(summary.marginPercent()).isEqualByComparingTo("63.33");
    }

    @Test
    void noRevenueHasNoMarginPercent() {
        SalesDtos.Profitability summary = CrmCostService.summary("EUR", List.of(), BigDecimal.ZERO);
        assertThat(summary.marginPercent()).isNull();
        assertThat(summary.quotedTotal()).isNull();
    }
}
