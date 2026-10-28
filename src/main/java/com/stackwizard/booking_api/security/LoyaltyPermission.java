package com.stackwizard.booking_api.security;

public enum LoyaltyPermission {
    /** Look up members, balances, history (reception, support). */
    READ,
    /** Manual nights/tier corrections and exceptions (program owner). */
    ADJUST,
    /** Program, rule sets, tiers, channel map. */
    CONFIG,
    /** Member and reservation CSV imports. */
    IMPORT,
    /** Outbox subscriptions (webhooks) and event replay. */
    INTEGRATION_CONFIG
}
