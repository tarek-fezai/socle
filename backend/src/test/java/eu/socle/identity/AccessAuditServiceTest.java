// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AccessAuditServiceTest {

    static final String ISSUER = "http://localhost:8081/realms/socle";

    @Mock AuditService auditService;

    MutableClock clock;
    AccessAuditService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = new AccessAuditService(auditService, clock);
    }

    @Test
    void accessDenied_isRateLimited_oneEntryPerUserAndReasonPer10Minutes() {
        assertThat(service.recordDenied(ISSUER, "sub-1", null, AccessDeniedReason.NOT_PROVISIONED)).isTrue();
        assertThat(service.recordDenied(ISSUER, "sub-1", null, AccessDeniedReason.NOT_PROVISIONED)).isFalse();

        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        assertThat(service.recordDenied(ISSUER, "sub-1", null, AccessDeniedReason.NOT_PROVISIONED)).isFalse();

        verify(auditService, times(1)).record(
                isNull(), eq(false), eq(AuditActions.AUTH_ACCESS_DENIED),
                eq("user"), isNull(), anyMap(), isNull());

        clock.advance(Duration.ofSeconds(2));
        assertThat(service.recordDenied(ISSUER, "sub-1", null, AccessDeniedReason.NOT_PROVISIONED)).isTrue();
        verify(auditService, times(2)).record(
                any(), eq(false), eq(AuditActions.AUTH_ACCESS_DENIED), anyString(), any(), anyMap(), any());
    }

    @Test
    void accessDenied_otherReasonOrOtherUser_isAuditedSeparately() {
        assertThat(service.recordDenied(ISSUER, "sub-1", null, AccessDeniedReason.NOT_PROVISIONED)).isTrue();
        assertThat(service.recordDenied(ISSUER, "sub-1", null, AccessDeniedReason.ACCOUNT_DISABLED)).isTrue();
        assertThat(service.recordDenied(ISSUER, "sub-2", null, AccessDeniedReason.NOT_PROVISIONED)).isTrue();
        assertThat(service.recordDenied("https://other-idp", "sub-1", null, AccessDeniedReason.NOT_PROVISIONED))
                .isTrue();

        verify(auditService, times(4)).record(
                any(), eq(false), eq(AuditActions.AUTH_ACCESS_DENIED), anyString(), any(), anyMap(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void accessDenied_metadataHasReasonAndIdentityButNoToken() {
        service.recordDenied(ISSUER, "sub-1", null, AccessDeniedReason.EMAIL_DOMAIN_NOT_ALLOWED);

        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(any(), eq(false), eq(AuditActions.AUTH_ACCESS_DENIED),
                anyString(), any(), meta.capture(), any());
        assertThat(meta.getValue())
                .containsEntry("reason", "email_domain_not_allowed")
                .containsEntry("issuer", ISSUER)
                .containsEntry("subject", "sub-1")
                .doesNotContainKeys("token", "jwt", "authorization");
    }

    @Test
    void accessGranted_recordsActorAndMode() {
        UUID id = UUID.randomUUID();
        service.recordGranted(id, ISSUER, "sub-1", IdentityProperties.AccessMode.REQUIRE_GROUP);

        verify(auditService).record(
                eq(id), eq(false), eq(AuditActions.AUTH_ACCESS_GRANTED),
                eq("user"), eq(id), anyMap(), isNull());
    }
}
