// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.user;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserIdentityServiceTest {

    static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID ACTOR = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final String ISSUER_A = "http://localhost:8081/realms/socle";
    static final String ISSUER_B = "https://login.microsoftonline.com/t/v2.0";

    @Mock UserIdentityRepository identityRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;

    UserIdentityService service;

    @BeforeEach
    void setUp() {
        service = new UserIdentityService(identityRepository, userRepository, auditService);
        when(userRepository.existsById(USER)).thenReturn(true);
        when(userRepository.existsById(ACTOR)).thenReturn(true);
        when(identityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void link_associatesNewIdentity_andAudits() {
        when(identityRepository.findByIssuerAndSubject(ISSUER_B, "oid-1")).thenReturn(Optional.empty());

        var view = service.link(ACTOR, USER, ISSUER_B, "oid-1");

        assertThat(view.userId()).isEqualTo(USER);
        assertThat(view.issuer()).isEqualTo(ISSUER_B);
        verify(auditService).record(
                eq(ACTOR), eq(false), eq(AuditActions.USER_IDENTITY_LINKED),
                eq("user"), eq(USER), any(), isNull());
    }

    @Test
    void unlink_lastIdentity_rejected() {
        when(identityRepository.findByUserIdAndIssuerAndSubject(USER, ISSUER_A, "sub-1"))
                .thenReturn(Optional.of(identity(USER, ISSUER_A, "sub-1")));
        when(identityRepository.countByUserId(USER)).thenReturn(1L);

        assertThatThrownBy(() -> service.unlink(ACTOR, USER, ISSUER_A, "sub-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("dernière identité");
        verify(identityRepository, never()).delete(any());
    }

    @Test
    void unlink_whenMultiple_allowed() {
        when(identityRepository.findByUserIdAndIssuerAndSubject(USER, ISSUER_A, "sub-1"))
                .thenReturn(Optional.of(identity(USER, ISSUER_A, "sub-1")));
        when(identityRepository.countByUserId(USER)).thenReturn(2L);

        service.unlink(ACTOR, USER, ISSUER_A, "sub-1");

        verify(identityRepository).delete(any());
        verify(auditService).record(
                eq(ACTOR), eq(false), eq(AuditActions.USER_IDENTITY_UNLINKED),
                eq("user"), eq(USER), any(), isNull());
    }

    @Test
    void import_dryRun_writesNothing() {
        String csv = "user_id,new_issuer,new_subject\n" + USER + "," + ISSUER_B + ",oid-9\n";
        when(identityRepository.findByIssuerAndSubject(ISSUER_B, "oid-9")).thenReturn(Optional.empty());

        var report = service.importIdentities(ACTOR, csv, true);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.applied()).hasSize(1);
        verify(identityRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(Boolean.class), eq(AuditActions.USER_IDENTITIES_IMPORTED),
                any(), any(), any(), any());
    }

    @Test
    void import_apply_idempotentSecondRun() {
        String csv = USER + "," + ISSUER_B + ",oid-9\n";
        when(identityRepository.findByIssuerAndSubject(ISSUER_B, "oid-9"))
                .thenReturn(Optional.empty()) // import #1 check
                .thenReturn(Optional.empty()) // link() check
                .thenReturn(Optional.of(identity(USER, ISSUER_B, "oid-9"))); // import #2

        var first = service.importIdentities(ACTOR, csv, false);
        assertThat(first.applied()).hasSize(1);
        verify(identityRepository).save(any());

        var second = service.importIdentities(ACTOR, csv, false);
        assertThat(second.skipped()).hasSize(1);
        assertThat(second.applied()).isEmpty();
    }

    @Test
    void import_conflict_whenIdentityOwnedByOther() {
        UUID other = UUID.randomUUID();
        String csv = USER + "," + ISSUER_B + ",oid-9\n";
        when(identityRepository.findByIssuerAndSubject(ISSUER_B, "oid-9"))
                .thenReturn(Optional.of(identity(other, ISSUER_B, "oid-9")));

        var report = service.importIdentities(ACTOR, csv, false);
        assertThat(report.conflicts()).hasSize(1);
        verify(identityRepository, never()).save(any());
    }

    private static UserIdentityEntity identity(UUID userId, String issuer, String subject) {
        UserIdentityEntity e = new UserIdentityEntity();
        e.setUserId(userId);
        e.setIssuer(issuer);
        e.setSubject(subject);
        e.setLinkedAt(Instant.now());
        return e;
    }
}
