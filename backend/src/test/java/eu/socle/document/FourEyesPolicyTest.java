// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FourEyesPolicyTest {

    static final UUID ALICE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID BOB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID CAROL = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID DAVE = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock JdbcTemplate jdbc;

    @Test
    void isConflict_requesterOrContributor() {
        assertThat(FourEyesPolicy.isConflict(ALICE, BOB, Set.of(ALICE))).isTrue();
        assertThat(FourEyesPolicy.isConflict(BOB, BOB, Set.of(ALICE))).isTrue();
        assertThat(FourEyesPolicy.isConflict(CAROL, BOB, Set.of(ALICE))).isFalse();
        assertThat(FourEyesPolicy.isConflict(null, BOB, Set.of(ALICE))).isFalse();
    }

    @Test
    void loadContentContributors_allVersionsWhenNeverApproved() {
        when(jdbc.query(contains("submitted_version_no"), any(ResultSetExtractor.class), eq(DOC)))
                .thenReturn(null);
        when(jdbc.query(contains("document_versions dv"), any(RowMapper.class), eq(DOC), eq(0)))
                .thenReturn(List.of(ALICE, BOB));
        when(jdbc.query(contains("COALESCE(updated_by"), any(RowMapper.class), eq(DOC)))
                .thenReturn(List.of(BOB));

        assertThat(FourEyesPolicy.loadContentContributors(jdbc, DOC))
                .containsExactlyInAnyOrder(ALICE, BOB);
    }

    @Test
    void loadContentContributors_onlyAfterLastApproved_plusUpdatedBy() {
        when(jdbc.query(contains("submitted_version_no"), any(ResultSetExtractor.class), eq(DOC)))
                .thenReturn(3);
        when(jdbc.query(contains("document_versions dv"), any(RowMapper.class), eq(DOC), eq(3)))
                .thenReturn(List.of(ALICE));
        when(jdbc.query(contains("COALESCE(updated_by"), any(RowMapper.class), eq(DOC)))
                .thenReturn(List.of(ALICE));

        Set<UUID> contributors = FourEyesPolicy.loadContentContributors(jdbc, DOC);
        assertThat(contributors).containsExactly(ALICE);
        assertThat(contributors).doesNotContain(DAVE);
        assertThat(FourEyesPolicy.isConflict(DAVE, BOB, contributors)).isFalse();
        assertThat(FourEyesPolicy.isConflict(CAROL, BOB, contributors)).isFalse();
        assertThat(FourEyesPolicy.isConflict(ALICE, BOB, contributors)).isTrue();
        assertThat(FourEyesPolicy.isConflict(BOB, BOB, contributors)).isTrue();
    }
}
