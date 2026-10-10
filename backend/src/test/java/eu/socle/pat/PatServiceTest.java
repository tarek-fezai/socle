// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.web.ApiErrors;
import eu.socle.web.CodedStatusException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatServiceTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    PatRepository repository;
    AuditService auditService;
    PatHasher hasher;
    PatService service;

    @BeforeEach
    void setUp() {
        repository = mock(PatRepository.class);
        auditService = mock(AuditService.class);
        hasher = new PatHasher("unit-test-pepper-unit-test-pepper-0000".getBytes(StandardCharsets.UTF_8));
        service = new PatService(repository, hasher, auditService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 91, -1, 365})
    void expiry_outOfBounds_400PatExpiryInvalid(int days) {
        assertThatThrownBy(() -> service.create(USER, "CI", PatScope.READ, days))
                .isInstanceOfSatisfying(CodedStatusException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ApiErrors.PAT_EXPIRY_INVALID);
                    assertThat(e.getStatusCode().value()).isEqualTo(400);
                });
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void expiry_missing_400PatExpiryInvalid() {
        assertThatThrownBy(() -> service.create(USER, "CI", PatScope.READ, null))
                .isInstanceOfSatisfying(CodedStatusException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiErrors.PAT_EXPIRY_INVALID));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 90})
    void expiry_bounds1And90_accepted_expiresAtExact(int days) {
        PatService.Created created = service.create(USER, "CI", PatScope.READ_WRITE, days);

        assertThat(created.row().expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(days)));
        assertThat(created.row().createdAt()).isEqualTo(NOW);
        assertThat(created.token()).startsWith("pat_");
    }

    @Test
    void limit_tenActive_409PatLimitReached() {
        when(repository.countActive(eq(USER), any())).thenReturn(PatService.MAX_ACTIVE_TOKENS);

        assertThatThrownBy(() -> service.create(USER, "CI", PatScope.READ, 30))
                .isInstanceOfSatisfying(CodedStatusException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ApiErrors.PAT_LIMIT_REACHED);
                    assertThat(e.getStatusCode().value()).isEqualTo(409);
                });
        verify(repository).lockUser(USER);
    }

    @Test
    void limit_nineActive_tenthAccepted() {
        when(repository.countActive(eq(USER), any())).thenReturn(PatService.MAX_ACTIVE_TOKENS - 1);
        assertThat(service.create(USER, "CI", PatScope.READ, 30).token()).isNotBlank();
        assertThat(PatService.MAX_ACTIVE_TOKENS).isEqualTo(10);
    }

    @Test
    void create_storesHmacOnly_auditsWithoutSecret() {
        PatService.Created created = service.create(USER, "  Script CI  ", PatScope.READ, 30);
        PatTokenFormat.Parsed parsed = PatTokenFormat.parse(created.token()).orElseThrow();

        ArgumentCaptor<byte[]> hash = ArgumentCaptor.forClass(byte[].class);
        verify(repository).insert(eq(created.row().id()), eq(USER), eq("Script CI"), eq(parsed.lookup()),
                hash.capture(), eq(created.row().last4()), eq(PatScope.READ), eq(NOW), any());
        assertThat(hash.getValue()).isEqualTo(hasher.hash(parsed.secret()));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> meta = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordSync(eq(USER), eq(false), eq(AuditActions.PAT_CREATED),
                eq("personal_access_token"), eq(created.row().id()), meta.capture(), isNull());
        assertThat(meta.getValue()).containsOnlyKeys("name", "last4", "scope", "expiresAt");
        assertThat(meta.getValue().toString()).doesNotContain(parsed.secret()).doesNotContain(created.token());
        assertThat(created.toString()).doesNotContain(parsed.secret());
    }

    @Test
    void create_nameBlankOrTooLong_400() {
        assertThatThrownBy(() -> service.create(USER, "   ", PatScope.READ, 30))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> service.create(USER, "x".repeat(101), PatScope.READ, 30))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> service.create(USER, "ok", null, 30))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void authenticate_validToken_thenGenericEmptyForEveryFailure() {
        PatTokenFormat.Generated g = PatTokenFormat.generate(new java.security.SecureRandom());
        UUID id = UUID.randomUUID();
        PatRepository.AuthRow row = new PatRepository.AuthRow(id, USER, hasher.hash(g.secret()), PatScope.READ,
                NOW.plus(Duration.ofDays(3)), null, null, "active", "u@example.com", "U");
        when(repository.findForAuthentication(g.lookup())).thenReturn(Optional.of(row));
        when(repository.firstIdentity(USER)).thenReturn(Optional.of(new PatRepository.Identity("http://iss", "sub")));

        assertThat(service.authenticate(g.token())).hasValueSatisfying(a -> {
            assertThat(a.userId()).isEqualTo(USER);
            assertThat(a.subject()).isEqualTo("sub");
        });

        String wrongSecret = "pat_" + g.lookup() + "_" + "A".repeat(PatTokenFormat.SECRET_LENGTH);
        assertThat(service.authenticate(wrongSecret)).isEmpty();
        assertThat(service.authenticate("pat_unknownlook_" + g.secret())).isEmpty();
        assertThat(service.authenticate("pat_garbage")).isEmpty();

        when(repository.findForAuthentication(g.lookup())).thenReturn(Optional.of(new PatRepository.AuthRow(
                id, USER, row.tokenHash(), PatScope.READ, NOW, null, null, "active", null, null)));
        assertThat(service.authenticate(g.token())).as("expiré (expires_at == now)").isEmpty();

        when(repository.findForAuthentication(g.lookup())).thenReturn(Optional.of(new PatRepository.AuthRow(
                id, USER, row.tokenHash(), PatScope.READ, NOW.plusSeconds(60), null, NOW, "active", null, null)));
        assertThat(service.authenticate(g.token())).as("révoqué").isEmpty();

        when(repository.findForAuthentication(g.lookup())).thenReturn(Optional.of(new PatRepository.AuthRow(
                id, USER, row.tokenHash(), PatScope.READ, NOW.plusSeconds(60), null, null, "disabled", null, null)));
        assertThat(service.authenticate(g.token())).as("compte désactivé").isEmpty();
    }

    @Test
    void lastUsed_writtenAtMostOncePerMinute() {
        PatTokenFormat.Generated g = PatTokenFormat.generate(new java.security.SecureRandom());
        UUID id = UUID.randomUUID();
        when(repository.findForAuthentication(g.lookup())).thenReturn(Optional.of(new PatRepository.AuthRow(
                id, USER, hasher.hash(g.secret()), PatScope.READ, NOW.plus(Duration.ofDays(3)),
                null, null, "active", null, null)));
        when(repository.firstIdentity(USER)).thenReturn(Optional.of(new PatRepository.Identity("http://iss", "sub")));

        for (int i = 0; i < 5; i++) {
            service.authenticate(g.token());
        }
        verify(repository, times(1)).touchLastUsed(id, NOW);

        when(repository.findForAuthentication(g.lookup())).thenReturn(Optional.of(new PatRepository.AuthRow(
                id, USER, hasher.hash(g.secret()), PatScope.READ, NOW.plus(Duration.ofDays(3)),
                NOW.minusSeconds(30), null, "active", null, null)));
        PatService fresh = new PatService(repository, hasher, auditService, Clock.fixed(NOW, ZoneOffset.UTC));
        fresh.authenticate(g.token());
        verify(repository, times(1)).touchLastUsed(any(), any());
    }

    @Test
    void revoke_otherUsersToken_false_noAudit() {
        when(repository.findByIdAndUser(any(), eq(USER))).thenReturn(Optional.empty());
        assertThat(service.revoke(USER, UUID.randomUUID())).isFalse();
        verify(auditService, never()).recordSync(any(), anyBoolean(), anyString(), any(), any(), any(), any());
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
