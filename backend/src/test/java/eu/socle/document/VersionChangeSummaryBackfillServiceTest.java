// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ancien décalage → résumés aux bons numéros ; second passage = skip (marqueur).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VersionChangeSummaryBackfillServiceTest {

    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock JdbcTemplate jdbc;

    VersionChangeSummaryBackfillService service;

    @BeforeEach
    void setUp() {
        service = new VersionChangeSummaryBackfillService(jdbc, new ObjectMapper());
        when(jdbc.queryForList(contains("FROM documents"))).thenReturn(List.of(Map.of(
                "id", DOC,
                "current_version_no", 4
        )));
        when(jdbc.queryForList(contains("FROM document_versions"), eq(DOC))).thenReturn(List.of(
                Map.of("version_no", 1, "change_summary", "S2"),
                Map.of("version_no", 2, "change_summary", "S3"),
                Map.of("version_no", 3, "change_summary", "S4")
        ));
        when(jdbc.update(any(String.class), any(), any(), any())).thenReturn(1);
        when(jdbc.update(any(String.class), any(), any())).thenReturn(1);
    }

    @Test
    void shiftSummaries_movesForward_andSetsCurrent() {
        Map<Integer, String> old = new HashMap<>();
        old.put(1, "S2");
        old.put(2, "S3");
        old.put(3, "S4");

        VersionChangeSummaryBackfillService.ShiftResult shifted =
                VersionChangeSummaryBackfillService.shiftSummaries(old);

        assertThat(shifted.archived()).hasSize(3);
        assertThat(shifted.archived().get(1)).isNull();
        assertThat(shifted.archived().get(2)).isEqualTo("S2");
        assertThat(shifted.archived().get(3)).isEqualTo("S3");
        assertThat(shifted.currentSummary()).isEqualTo("S4");
    }

    @Test
    void shiftSummaries_empty_isNoOp() {
        VersionChangeSummaryBackfillService.ShiftResult shifted =
                VersionChangeSummaryBackfillService.shiftSummaries(Map.of());
        assertThat(shifted.archived()).isEmpty();
        assertThat(shifted.currentSummary()).isNull();
    }

    @Test
    void backfill_writesMarker_secondPassSkipped() {
        when(jdbc.queryForObject(contains("authz_migrations"), eq(Integer.class), any()))
                .thenReturn(0, 1);

        VersionChangeSummaryBackfillService.BackfillReport first = service.backfill(false);
        assertThat(first.skipped()).isFalse();
        assertThat(first.versionsShifted()).isEqualTo(3);
        verify(jdbc, atLeastOnce()).update(
                argThat((String sql) -> sql != null && sql.contains("authz_migrations")),
                eq(VersionChangeSummaryBackfillService.BACKFILL_NAME),
                anyString());
        verify(jdbc, atLeastOnce()).update(
                argThat((String sql) -> sql != null && sql.contains("current_change_summary")),
                eq("S4"),
                eq(DOC));

        VersionChangeSummaryBackfillService.BackfillReport second = service.backfill(false);
        assertThat(second.skipped()).isTrue();
        assertThat(second.versionsShifted()).isZero();
    }
}
