package com.stackwizard.booking_api.service.loyalty.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.model.LoyaltyQualificationStep;

/** One step of the qualification pipeline; which steps run, in which order, with which params is configuration. */
public interface QualificationRule {
    LoyaltyQualificationStep.RuleCode code();

    /** {@code null} detail means pass; otherwise a short human explanation stored with the reject code. */
    Outcome check(QualificationContext ctx, JsonNode params);

    record Outcome(boolean passed, String detail) {
        public static Outcome pass() {
            return new Outcome(true, null);
        }

        public static Outcome pass(String detail) {
            return new Outcome(true, detail);
        }

        public static Outcome fail(String detail) {
            return new Outcome(false, detail);
        }
    }
}
