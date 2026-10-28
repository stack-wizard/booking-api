package com.stackwizard.booking_api.service.loyalty.imports;

import com.stackwizard.booking_api.model.LoyaltyImportBatch;

import java.util.List;
import java.util.Map;

/** One import type (members, reservations): its fields, row validation and the per-row apply. */
public interface ImportApplier {
    LoyaltyImportBatch.ImportType type();

    /** Canonical fields with the header aliases recognised automatically (first is the documented name). */
    Map<String, List<String>> fields();

    /** Per-batch state for validation (duplicate detection across the file). */
    default Object newValidationState(LoyaltyImportBatch batch) {
        return null;
    }

    /** Fills {@code row.errors()}; returns a duplicate key reason or {@code null}. */
    String validate(LoyaltyImportBatch batch, ImportRow row, Object state);

    /** Applies one valid row inside its own transaction; returns the target id (member / reservation). */
    Long apply(LoyaltyImportBatch batch, ImportRow row);

    /** Runs once after every row is applied (e.g. bump the member number sequence). */
    default void afterApply(LoyaltyImportBatch batch) {
    }
}
