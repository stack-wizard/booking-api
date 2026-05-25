package com.stackwizard.booking_api.service.opera;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperaCheckInOrchestratorTest {

    @Test
    void formatDepositPostingReferenceAppendsCardTypeToFiscalNumber() {
        assertThat(OperaCheckInOrchestrator.formatDepositPostingReference("1234567890123", "VISA"))
                .isEqualTo("1234567890123 VISA");
    }

    @Test
    void formatDepositPostingReferenceKeepsFiscalNumberWhenCardTypeMissing() {
        assertThat(OperaCheckInOrchestrator.formatDepositPostingReference("1234567890123", null))
                .isEqualTo("1234567890123");
        assertThat(OperaCheckInOrchestrator.formatDepositPostingReference("1234567890123", " "))
                .isEqualTo("1234567890123");
    }
}
