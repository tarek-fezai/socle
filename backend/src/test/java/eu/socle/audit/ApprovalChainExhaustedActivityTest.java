package eu.socle.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.document.ApprovalActivitiesImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ApprovalChainExhaustedActivityTest {

    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID REQUESTER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID LAST_APPROVER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID REQUEST_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Test
    void chainExhausted_notifiesRequesterAndLastApprover_andAudits() {
        List<UUID> notifiedUsers = new ArrayList<>();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class, invocation -> {
            String method = invocation.getMethod().getName();
            Object[] args = invocation.getArguments();
            if ("update".equals(method)) {
                String sql = String.valueOf(args[0]);
                if (sql.contains("notifications") && args.length >= 3) {
                    notifiedUsers.add((UUID) args[2]);
                }
                return 1;
            }
            if ("query".equals(method)) {
                return List.of(LAST_APPROVER);
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });

        AuditService auditService = mock(AuditService.class);
        ApprovalActivitiesImpl activities = new ApprovalActivitiesImpl(
                jdbcTemplate, auditService, mock(eu.socle.document.ReliabilityScoreService.class),
                mock(eu.socle.storage.DocumentStore.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
        String result = activities.recordChainExhausted(
                DOC, REQUEST_ID, 2, REQUESTER, ApprovalActivitiesImpl.SYSTEM_ACTOR_ID);

        assertThat(result).isEqualTo("en_cours_alerte");
        assertThat(notifiedUsers).containsExactlyInAnyOrder(REQUESTER, LAST_APPROVER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordSync(
                isNull(), eq(true), eq(AuditActions.APPROVAL_CHAIN_EXHAUSTED),
                eq("document"), eq(DOC), meta.capture(), isNull());
        assertThat(meta.getValue()).containsEntry("lastStepOrder", 2);
        assertThat(meta.getValue()).containsEntry("stepsTraversed", 2);
        assertThat(meta.getValue().get("notifiedUserIds")).asList()
                .containsExactlyInAnyOrder(REQUESTER.toString(), LAST_APPROVER.toString());
    }

    @Test
    void chainExhausted_propagatesNotificationInsertFailure() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class, invocation -> {
            if ("update".equals(invocation.getMethod().getName())) {
                String sql = String.valueOf(invocation.getArguments()[0]);
                if (sql.contains("notifications")) {
                    throw new RuntimeException("notifications unavailable");
                }
                return 1;
            }
            if ("query".equals(invocation.getMethod().getName())) {
                return List.of();
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });

        ApprovalActivitiesImpl activities = new ApprovalActivitiesImpl(
                jdbcTemplate, mock(AuditService.class),
                mock(eu.socle.document.ReliabilityScoreService.class),
                mock(eu.socle.storage.DocumentStore.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> activities.recordChainExhausted(
                DOC, REQUEST_ID, 1, REQUESTER, ApprovalActivitiesImpl.SYSTEM_ACTOR_ID))
                .hasMessageContaining("notifications unavailable");
    }

    @Test
    void asyncRecord_incrementsFailureMetric() {
        AtomicBoolean called = new AtomicBoolean();
        JdbcTemplate failingJdbc = mock(JdbcTemplate.class, invocation -> {
            String name = invocation.getMethod().getName();
            if ("queryForObject".equals(name) || "update".equals(name)) {
                called.set(true);
                throw new RuntimeException("insert failed");
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuditService service = new AuditService(failingJdbc, new ObjectMapper(), registry);

        service.record(REQUESTER, false, AuditActions.DOCUMENT_CREATED,
                "document", DOC, Map.of(), null);

        assertThat(called).isTrue();
        assertThat(registry.counter(AuditService.METRIC_WRITE_FAILURES).count()).isEqualTo(1.0);
    }

    @Test
    void recordSync_throwsAuditWriteException() {
        JdbcTemplate failingJdbc = mock(JdbcTemplate.class, invocation -> {
            String name = invocation.getMethod().getName();
            if ("queryForObject".equals(name) || "update".equals(name)) {
                throw new RuntimeException("insert failed");
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
        AuditService service = new AuditService(failingJdbc, new ObjectMapper(), new SimpleMeterRegistry());

        assertThatThrownBy(() -> service.recordSync(
                REQUESTER, false, AuditActions.ACCESS_GRANTED,
                "space", DOC, Map.of(), null))
                .isInstanceOf(AuditService.AuditWriteException.class);
    }
}
