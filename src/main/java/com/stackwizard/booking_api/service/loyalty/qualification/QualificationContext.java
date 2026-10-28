package com.stackwizard.booking_api.service.loyalty.qualification;

import com.stackwizard.booking_api.model.LoyaltyChannelMap;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.LoyaltyReservation;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.service.loyalty.LoyaltyParams;

import java.time.LocalDate;
import java.util.List;

/**
 * Input of one evaluation. {@code nights} starts as every night of the stay; rules such as ACTIVATION or
 * UNIQUE_PER_DATE narrow it, and a rule rejects when nothing is left.
 */
public final class QualificationContext {
    private final LoyaltyReservation reservation;
    private final LoyaltyMember member;
    private final String memberLastName;
    private final LoyaltyRuleSet ruleSet;
    private final LoyaltyParams params;
    private final List<LoyaltyChannelMap> channelMap;
    private List<LocalDate> nights;

    public QualificationContext(LoyaltyReservation reservation, LoyaltyMember member, String memberLastName,
                                LoyaltyRuleSet ruleSet, LoyaltyParams params, List<LoyaltyChannelMap> channelMap,
                                List<LocalDate> nights) {
        this.reservation = reservation;
        this.member = member;
        this.memberLastName = memberLastName;
        this.ruleSet = ruleSet;
        this.params = params;
        this.channelMap = channelMap;
        this.nights = nights;
    }

    public LoyaltyReservation reservation() {
        return reservation;
    }

    public LoyaltyMember member() {
        return member;
    }

    public String memberLastName() {
        return memberLastName;
    }

    public LoyaltyRuleSet ruleSet() {
        return ruleSet;
    }

    public LoyaltyParams params() {
        return params;
    }

    public List<LoyaltyChannelMap> channelMap() {
        return channelMap;
    }

    public List<LocalDate> nights() {
        return nights;
    }

    public void nights(List<LocalDate> nights) {
        this.nights = List.copyOf(nights);
    }
}
