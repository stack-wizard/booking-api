package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.PaymentCardType;
import com.stackwizard.booking_api.repository.PaymentCardTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCardTypeServiceTest {

    @Mock
    private PaymentCardTypeRepository repo;

    @InjectMocks
    private PaymentCardTypeService service;

    @Test
    void saveNormalizesCodeAndDefaultsName() {
        PaymentCardType source = PaymentCardType.builder()
                .tenantId(1L)
                .code(" visa ")
                .build();

        when(repo.save(any(PaymentCardType.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentCardType saved = service.save(source);

        assertThat(saved.getCode()).isEqualTo("VISA");
        assertThat(saved.getName()).isEqualTo("VISA");
        assertThat(saved.getActive()).isTrue();
    }

    @Test
    void requireActiveCodeRejectsUnknownCardType() {
        when(repo.findByTenantIdAndCodeIgnoreCaseAndActiveTrue(1L, "VISA")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireActiveCode(1L, "visa"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VISA");
    }
}
