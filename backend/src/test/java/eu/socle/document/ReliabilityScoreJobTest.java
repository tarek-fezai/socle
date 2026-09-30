package eu.socle.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Job périodique + horloge injectée (pas de sleep 24h).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReliabilityScoreJobTest {

    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock JdbcTemplate jdbcTemplate;

    MutableClock clock;
    ReliabilityScoreService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-28T12:00:00Z"));
        service = new ReliabilityScoreService(
                jdbcTemplate, new ReliabilityScoreProperties(), clock, mock(ApplicationEventPublisher.class));
    }

    @Test
    void staleJob_recalculatesWhenComputedAtOlderThan24h() {
        Instant staleAt = clock.instant().minusSeconds(25 * 3600);
        when(jdbcTemplate.query(contains("reliability_computed_at"), any(RowMapper.class), any(Timestamp.class)))
                .thenReturn(List.of(DOC));

        stubFullRecalculate(staleAt);

        int n = service.recalculateStaleValideDocuments();
        assertThat(n).isEqualTo(1);

        ArgumentCaptor<Object> scoreCap = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(
                contains("SET reliability_score"),
                scoreCap.capture(),
                any(Timestamp.class),
                eq(DOC));
        assertThat(scoreCap.getValue()).isInstanceOf(BigDecimal.class);
    }

    @Test
    void scheduler_delegatesToService() {
        ReliabilityScoreScheduler scheduler = new ReliabilityScoreScheduler(service);
        when(jdbcTemplate.query(contains("reliability_computed_at"), any(RowMapper.class), any(Timestamp.class)))
                .thenReturn(List.of());
        scheduler.refreshStaleScores();
        verify(jdbcTemplate).query(contains("reliability_computed_at"), any(RowMapper.class), any(Timestamp.class));
    }

    @SuppressWarnings("unchecked")
    private void stubFullRecalculate(Instant ignoredStaleMarker) {
        when(jdbcTemplate.query(contains("is_mandatory_ack"), any(RowMapper.class), eq(DOC)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(DOC);
                    when(rs.getObject("space_id")).thenReturn(SPACE);
                    when(rs.getString("status")).thenReturn("valide");
                    when(rs.getBoolean("is_mandatory_ack")).thenReturn(false);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.query(contains("audit_log_events"), any(RowMapper.class), eq(DOC), anyString()))
                .thenReturn(List.of(Timestamp.from(clock.instant().minusSeconds(3600))));
        when(jdbcTemplate.query(contains("retention_policies"), any(RowMapper.class), eq(SPACE), eq(DOC)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(contains("document_comments"), any(ResultSetExtractor.class), eq(DOC)))
                .thenAnswer(inv -> {
                    ResultSetExtractor<?> ext = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.next()).thenReturn(true);
                    when(rs.getInt("total")).thenReturn(0);
                    when(rs.getInt("resolved")).thenReturn(0);
                    return ext.extractData(rs);
                });
        when(jdbcTemplate.query(contains("attestation_campaigns"), any(RowMapper.class), eq(DOC)))
                .thenReturn(List.of());
        when(jdbcTemplate.update(contains("SET reliability_score"), any(), any(), eq(DOC))).thenReturn(1);
    }

    /** Horloge mutable pour simuler le passage du temps sans sleep. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneOffset zone = ZoneOffset.UTC;

        MutableClock(Instant initial) {
            this.instant = new AtomicReference<>(initial);
        }

        void advance(java.time.Duration d) {
            instant.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneOffset getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
