package com.stackwizard.booking_api.dto;

import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmAccountRelation;

import java.time.OffsetDateTime;

public final class CrmTeamDtos {
    private CrmTeamDtos() {
    }

    public record AssignRequest(Long ownerUserId, Long teamId) {
    }

    /** Open work of one user that a reassignment would move. */
    public record ReassignCounts(int leads, int opportunities, int accounts, int events) {
    }

    public record ReassignRequest(Long fromUserId,
                                  Long toUserId,
                                  Long toTeamId,
                                  Boolean includeLeads,
                                  Boolean includeOpportunities,
                                  Boolean includeAccounts,
                                  Boolean includeEvents,
                                  Boolean endMemberships,
                                  String note) {
    }

    public record RelationRequest(Long toAccountId, CrmAccountRelation.Type relationType, String note) {
    }

    /**
     * One edge seen from the requested account. {@code kind} is PARENT / CHILD for the account hierarchy or the
     * relation type; {@code outgoing} tells whether the requested account is the "from" side.
     */
    public record RelationView(Long relationId,
                               String kind,
                               boolean outgoing,
                               Long accountId,
                               String accountName,
                               CrmAccount.AccountType accountType,
                               String note,
                               OffsetDateTime createdAt) {
    }
}
