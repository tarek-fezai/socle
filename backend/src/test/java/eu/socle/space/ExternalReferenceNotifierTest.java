// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.space;

import eu.socle.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExternalReferenceNotifierTest {

    static final UUID SPACE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock JdbcTemplate jdbc;
    @Mock NotificationService notificationService;

    @Test
    void firstPair_insertsAndNotifiesOwners_secondPairCall_skipped() {
        AtomicInteger inserts = new AtomicInteger();
        when(jdbc.update(anyString(), ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any()))
                .thenAnswer(inv -> inserts.getAndIncrement() == 0 ? 1 : 0);
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UUID>>any(), eq(SPACE_A)))
                .thenReturn(List.of(OWNER));
        when(jdbc.query(anyString(), ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<String>>any(), any()))
                .thenReturn("Espace");

        ExternalReferenceNotifier notifier = new ExternalReferenceNotifier(jdbc, notificationService);
        notifier.notifyOwnersOnFirstPair(SPACE_B, SPACE_A);
        notifier.notifyOwnersOnFirstPair(SPACE_B, SPACE_A);

        verify(notificationService, times(1))
                .create(eq(OWNER), eq(ExternalReferenceNotifier.NOTIFICATION_TYPE), any());
    }

    @Test
    void sameSpace_noop() {
        ExternalReferenceNotifier notifier = new ExternalReferenceNotifier(jdbc, notificationService);
        notifier.notifyOwnersOnFirstPair(SPACE_A, SPACE_A);
        verify(notificationService, never()).create(any(), any(), any());
    }
}
