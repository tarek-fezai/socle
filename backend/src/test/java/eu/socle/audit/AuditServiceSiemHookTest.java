package eu.socle.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceSiemHookTest {

    static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock JdbcTemplate jdbcTemplate;
    @Mock SiemDeliveryEnqueueService siemEnqueue;

    @Test
    void successfulInsert_enqueuesSiem() {
        when(jdbcTemplate.queryForObject(
                contains("RETURNING id"), eq(Long.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(99L);

        AuditService service = new AuditService(
                jdbcTemplate, new ObjectMapper(), new SimpleMeterRegistry(), siemEnqueue);

        service.recordSync(ACTOR, false, AuditActions.ACCESS_GRANTED,
                "document", DOC, Map.of("relation", "viewer"), null);

        verify(siemEnqueue).enqueueAfterAudit(
                eq(99L), eq(ACTOR), eq(false), eq(AuditActions.ACCESS_GRANTED),
                eq("document"), eq(DOC), anyMap(), any());
    }

    @Test
    void failedInsert_doesNotEnqueueSiem() {
        when(jdbcTemplate.queryForObject(
                contains("RETURNING id"), eq(Long.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        AuditService service = new AuditService(
                jdbcTemplate, new ObjectMapper(), new SimpleMeterRegistry(), siemEnqueue);

        assertThatThrownBy(() -> service.recordSync(
                ACTOR, false, AuditActions.ACCESS_GRANTED,
                "document", DOC, Map.of(), null))
                .isInstanceOf(AuditService.AuditWriteException.class);

        verify(siemEnqueue, never()).enqueueAfterAudit(
                anyLong(), any(), anyBoolean(), anyString(), anyString(), any(), anyMap(), any());
    }

    @Test
    void nullSiemService_stillWritesAudit() {
        when(jdbcTemplate.queryForObject(
                contains("RETURNING id"), eq(Long.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);

        AuditService service = new AuditService(
                jdbcTemplate, new ObjectMapper(), new SimpleMeterRegistry());

        service.recordSync(ACTOR, false, AuditActions.ACCESS_GRANTED,
                "space", DOC, Map.of(), null);
    }
}
