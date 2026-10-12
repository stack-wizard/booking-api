package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CrmLookup;
import com.stackwizard.booking_api.repository.CrmLookupRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrmLookupServiceTest {

    @Mock CrmLookupRepository repo;
    @Mock JdbcTemplate jdbc;
    @Mock CrmAccessContext accessContext;

    CrmLookupService service;

    @BeforeEach
    void setUp() {
        TenantContext.setScope(1L, 2L);
        service = new CrmLookupService(repo, jdbc, accessContext);
        when(repo.existsByTenantIdAndKind(eq(1L), any())).thenReturn(true);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private CrmLookup entry(String code, boolean active) {
        return CrmLookup.builder().id(7L).tenantId(1L).kind(CrmLookup.Kind.SETUP_STYLE)
                .code(code).label(code).displayOrder(10).active(active).build();
    }

    private void styles(CrmLookup... entries) {
        when(repo.findByTenantIdAndKindOrderByDisplayOrderAscIdAsc(1L, CrmLookup.Kind.SETUP_STYLE))
                .thenReturn(List.of(entries));
    }

    @Test
    void defaultsAreInsertedOnFirstRead() {
        when(repo.existsByTenantIdAndKind(1L, CrmLookup.Kind.SETUP_STYLE)).thenReturn(false);
        styles();

        service.list(CrmLookup.Kind.SETUP_STYLE);

        verify(jdbc, times(CrmLookupService.DEFAULTS.get(CrmLookup.Kind.SETUP_STYLE).size()))
                .update(anyString(), eq(1L), eq("SETUP_STYLE"), any(), any(), any());
    }

    @Test
    void existingListIsNotReseeded() {
        styles(entry("THEATRE", true));

        service.list(CrmLookup.Kind.SETUP_STYLE);

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void codeIsMatchedCaseInsensitively() {
        styles(entry("THEATRE", true));

        assertThat(service.requireCode(1L, CrmLookup.Kind.SETUP_STYLE, " theatre ", null)).isEqualTo("THEATRE");
        assertThat(service.requireCode(1L, CrmLookup.Kind.SETUP_STYLE, " ", null)).isNull();
    }

    @Test
    void inactiveCodeIsRejectedForNewValuesButKeptOnExistingRecords() {
        styles(entry("CABARET", false));

        assertThatThrownBy(() -> service.requireCode(1L, CrmLookup.Kind.SETUP_STYLE, "CABARET", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown setup style");
        assertThat(service.requireCode(1L, CrmLookup.Kind.SETUP_STYLE, "cabaret", "CABARET")).isEqualTo("CABARET");
    }

    @Test
    void createNormalizesCodeAndRequiresConfigPermission() {
        styles(entry("THEATRE", true));

        CrmLookup saved = service.create(CrmLookup.builder().kind(CrmLookup.Kind.SETUP_STYLE)
                .code("fish bowl").label(" Fish bowl ").build());

        verify(accessContext).require(CrmPermission.PIPELINE_CONFIG);
        assertThat(saved.getTenantId()).isEqualTo(1L);
        assertThat(saved.getCode()).isEqualTo("FISH_BOWL");
        assertThat(saved.getLabel()).isEqualTo("Fish bowl");
        assertThat(saved.getDisplayOrder()).isEqualTo(20);
        assertThat(saved.getActive()).isTrue();
    }

    @Test
    void duplicateCodeIsRejected() {
        styles(entry("THEATRE", true));

        assertThatThrownBy(() -> service.create(CrmLookup.builder().kind(CrmLookup.Kind.SETUP_STYLE)
                .code("theatre").label("Theatre").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void codeCannotBeRenamed() {
        when(repo.findByIdAndTenantId(7L, 1L)).thenReturn(Optional.of(entry("THEATRE", true)));

        assertThatThrownBy(() -> service.update(7L, CrmLookup.builder().code("STAGE").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be changed");
    }
}
