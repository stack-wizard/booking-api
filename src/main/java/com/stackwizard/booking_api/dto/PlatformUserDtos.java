package com.stackwizard.booking_api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PlatformUserDtos {
    private PlatformUserDtos() {
    }

    /**
     * A Mikos Platform user that works for the organization or one of its hotels.
     * {@code appUserId} is set once the person has a booking user; {@code ready} means the token would let them in
     * (BOOKING application and a booking role); {@code propertyTenantIds} lists the hotels where they are members
     * ({@code orgMember} = member of the whole chain).
     */
    public record PlatformUserDto(UUID platformUserId,
                                  String username,
                                  List<String> roles,
                                  boolean ready,
                                  boolean orgMember,
                                  List<Long> propertyTenantIds,
                                  Long appUserId) {
    }

    public record AddPlatformMemberRequest(UUID platformUserId, Boolean teamLead, LocalDate validFrom, LocalDate validTo) {
    }
}
