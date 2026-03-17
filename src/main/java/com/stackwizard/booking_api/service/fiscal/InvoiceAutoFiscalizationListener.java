package com.stackwizard.booking_api.service.fiscal;

import com.stackwizard.booking_api.dto.InvoiceFiscalizeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class InvoiceAutoFiscalizationListener {
    private static final Logger log = LoggerFactory.getLogger(InvoiceAutoFiscalizationListener.class);

    private final InvoiceFiscalizationService invoiceFiscalizationService;

    public InvoiceAutoFiscalizationListener(InvoiceFiscalizationService invoiceFiscalizationService) {
        this.invoiceFiscalizationService = invoiceFiscalizationService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvoiceAutoFiscalizationRequested(InvoiceAutoFiscalizationRequestedEvent event) {
        if (event == null || event.invoiceId() == null) {
            return;
        }
        try {
            invoiceFiscalizationService.fiscalizeInvoice(event.invoiceId(), new InvoiceFiscalizeRequest());
        } catch (RuntimeException ex) {
            log.error("Automatic fiscalization failed for invoice {}", event.invoiceId(), ex);
        }
    }
}
