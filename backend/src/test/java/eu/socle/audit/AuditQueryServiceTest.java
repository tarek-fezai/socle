// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditQueryServiceTest {

    static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock JdbcTemplate jdbcTemplate;

    AuditQueryService service;

    @BeforeEach
    void setUp() {
        service = new AuditQueryService(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    }

    @Test
    void list_passesActorIdDateActionAndPaginationToSql() {
        Instant since = Instant.parse("2026-09-01T00:00:00Z");
        Instant until = Instant.parse("2026-09-30T23:59:59Z");

        service.list("document", DOC, ACTOR, "access.*", since, until, 50, 25);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(countSql.capture(), eq(Long.class), countArgs.capture());
        assertThat(countSql.getValue()).contains("e.resource_type = ?");
        assertThat(countSql.getValue()).contains("e.resource_id = ?");
        assertThat(countSql.getValue()).contains("e.actor_id = ?");
        assertThat(countSql.getValue()).contains("e.created_at >= ?");
        assertThat(countSql.getValue()).contains("e.created_at <= ?");
        assertThat(countSql.getValue()).contains("e.action LIKE ?");
        assertThat(countArgs.getValue()).contains(ACTOR, DOC, "document", "access.%");

        ArgumentCaptor<String> pageSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> pageArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(pageSql.capture(), any(RowMapper.class), pageArgs.capture());
        assertThat(pageSql.getValue()).contains("LEFT JOIN users");
        assertThat(pageSql.getValue()).contains("LIMIT ? OFFSET ?");
        Object[] args = pageArgs.getValue();
        assertThat(args[args.length - 2]).isEqualTo(25);
        assertThat(args[args.length - 1]).isEqualTo(50);
    }

    @Test
    void actionFilter_exactAndPrefixAndList() {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        AuditQueryService.appendActionFilter(where, args, "access.granted,document.*");
        assertThat(where.toString()).contains("e.action = ?");
        assertThat(where.toString()).contains("e.action LIKE ?");
        assertThat(args).containsExactly("access.granted", "document.%");
    }
}
