package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CrmTeam;
import com.stackwizard.booking_api.model.CrmTeamProperty;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.repository.CrmTeamMemberRepository;
import com.stackwizard.booking_api.repository.CrmTeamPropertyRepository;
import com.stackwizard.booking_api.repository.CrmTeamRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrmTeamDirectoryCoverageTest {
    private CrmTeamDirectory directory;

    private static CrmTeam team(long id) {
        return CrmTeam.builder().id(id).tenantId(1L).name("t" + id).active(true).build();
    }

    private static CrmTeamProperty row(long team, long hotel) {
        return CrmTeamProperty.builder().tenantId(1L).teamId(team).propertyTenantId(hotel).build();
    }

    @BeforeEach
    void setUp() {
        CrmTeamRepository teams = mock(CrmTeamRepository.class);
        CrmTeamPropertyRepository rows = mock(CrmTeamPropertyRepository.class);
        // 10 = chain-wide, 11 = hotels 2+3, 12 = hotel 2 only
        when(teams.findByTenantIdOrderByNameAsc(1L)).thenReturn(List.of(team(10), team(11), team(12)));
        when(rows.findByTenantId(1L)).thenReturn(List.of(row(11, 2), row(11, 3), row(12, 2)));
        directory = new CrmTeamDirectory(mock(CrmTeamMemberRepository.class), mock(AppUserRepository.class),
                mock(CrmAccessContext.class), teams, rows);
    }

    @Test
    void chainWideTeamAcceptsAnyRecord() {
        assertThat(directory.resolveProperty(1L, 10L, null)).isNull();
        assertThat(directory.resolveProperty(1L, 10L, 3L)).isEqualTo(3L);
        directory.requireCovers(1L, 10L, null);
    }

    @Test
    void singleHotelTeamDefaultsTheRecordToItsHotel() {
        assertThat(directory.resolveProperty(1L, 12L, null)).isEqualTo(2L);
    }

    @Test
    void multiHotelTeamAsksForAChoiceAndRejectsOtherHotels() {
        assertThatThrownBy(() -> directory.resolveProperty(1L, 11L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolveProperty(1L, 11L, 3L)).isEqualTo(3L);
        assertThatThrownBy(() -> directory.resolveProperty(1L, 11L, 4L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> directory.requireCovers(1L, 12L, 3L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> directory.requireCovers(1L, 12L, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordWithoutTeamIsNotChecked() {
        assertThat(directory.resolveProperty(1L, null, 3L)).isEqualTo(3L);
        directory.requireCovers(1L, null, 3L);
    }
}
