package com.stackwizard.booking_api.model;

public enum InvoiceType {
    INVOICE,
    DEPOSIT,
    INVOICE_STORNO,
    DEPOSIT_STORNO,
    ROOM_CHARGE,
    CREDIT_NOTE;

    public boolean isStornoType() {
        return this == INVOICE_STORNO || this == DEPOSIT_STORNO;
    }

    public OperaPostingTarget defaultOperaPostingTarget() {
        return this == ROOM_CHARGE ? OperaPostingTarget.RESERVATION : OperaPostingTarget.POSTING_MASTER;
    }
}
