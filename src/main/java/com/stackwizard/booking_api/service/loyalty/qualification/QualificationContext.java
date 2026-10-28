package com.stackwizard.booking_api.service.loyalty.qualification;

import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.LoyaltyChannelMap;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.LoyaltyProgram;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.model.PmsReservation;
import com.stackwizard.booking_api.service.loyalty.LoyaltyParams;

import java.time.LocalDate;
import java.util.List;

/**
 * Input of one evaluation. {@code nights} starts as every night of the stay; rules such as ACTIVATION or
 * UNIQUE_PER_DATE narrow it, and a rule rejects when nothing is left.
 */
public final class QualificationContext {
    private final PmsReservation reservation;
    private final CrmContact contact;
    private final LoyaltyMember member;
    private final LoyaltyProgram program;
    private final LoyaltyRuleSet ruleSet;
    private final LoyaltyParams params;
    private final List<LoyaltyChannelMap> channelMap;
    private List<LocalDate> nights;

    public QualificationContext(PmsReservation reservation, CrmContact contact, LoyaltyMember member, LoyaltyProgram program,
                                LoyaltyRuleSet ruleSet, LoyaltyParams params, List<LoyaltyChannelMap> channelMap,
                                List<LocalDate> nights) {
        this.reservation = reservation;
        this.contact = contact;
        this.member = member;
        this.program = program;
        this.ruleSet = ruleSet;
        this.params = params;
        this.channelMap = channelMap;
        this.nights = nights;
    }

    public PmsReservation reservation() {
        return reservation;
    }

    public CrmContact contact() {
        return contact;
    }

    public LoyaltyMember member() {
        return member;
    }

    public LoyaltyProgram program() {
        return program;
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
