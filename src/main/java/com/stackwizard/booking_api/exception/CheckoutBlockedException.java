package com.stackwizard.booking_api.exception;

import java.util.List;

public class CheckoutBlockedException extends RuntimeException {
    private final List<String> blockers;

    public CheckoutBlockedException(List<String> blockers) {
        super(blockers == null || blockers.isEmpty()
                ? "Checkout blocked"
                : String.join("; ", blockers));
        this.blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }

    public List<String> getBlockers() {
        return blockers;
    }
}
