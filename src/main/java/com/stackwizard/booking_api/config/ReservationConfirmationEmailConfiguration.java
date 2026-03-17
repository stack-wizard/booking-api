package com.stackwizard.booking_api.config;

import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.service.PaymentService;
import com.stackwizard.booking_api.service.ReservationConfirmationEmailListener;
import com.stackwizard.booking_api.service.ReservationConfirmationEmailRenderer;
import com.stackwizard.booking_api.service.ReservationConfirmationEmailService;
import com.stackwizard.booking_api.service.TenantEmailConfigResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = {
        "org.springframework.mail.javamail.JavaMailSenderImpl",
        "org.springframework.mail.javamail.MimeMessageHelper"
})
public class ReservationConfirmationEmailConfiguration {

    @Bean(name = "reservationConfirmationEmailExecutor")
    Executor reservationConfirmationEmailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("reservation-email-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.initialize();
        return executor;
    }

    @Bean
    ReservationConfirmationEmailService reservationConfirmationEmailService(
            ReservationRequestRepository reservationRequestRepository,
            ReservationRepository reservationRepository,
            ProductRepository productRepository,
            PaymentService paymentService,
            ReservationConfirmationEmailRenderer renderer,
            TenantEmailConfigResolver tenantEmailConfigResolver) {
        return new ReservationConfirmationEmailService(
                reservationRequestRepository,
                reservationRepository,
                productRepository,
                paymentService,
                renderer,
                tenantEmailConfigResolver
        );
    }

    @Bean
    ReservationConfirmationEmailListener reservationConfirmationEmailListener(
            ReservationConfirmationEmailService reservationConfirmationEmailService) {
        return new ReservationConfirmationEmailListener(reservationConfirmationEmailService);
    }
}
