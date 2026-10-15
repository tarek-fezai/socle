// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditServiceAfterCommitTest {

    @AfterEach
    void clearTx() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void activeTransaction_insertsOnlyAfterCommit() {
        AtomicInteger inserts = new AtomicInteger();
        AuditService service = service(inserts);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.record(null, true, "document.created", "document", UUID.randomUUID(), null, null);

        assertThat(inserts).hasValue(0);
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        assertThat(inserts).hasValue(1);
    }

    @Test
    void rolledBackTransaction_insertsNothing() {
        AtomicInteger inserts = new AtomicInteger();
        AuditService service = service(inserts);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.record(null, true, "document.created", "document", UUID.randomUUID(), null, null);
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        assertThat(inserts).hasValue(0);
    }

    @Test
    void withoutTransaction_insertsImmediately() {
        AtomicInteger inserts = new AtomicInteger();
        AuditService service = service(inserts);

        service.record(null, true, "document.created", "document", UUID.randomUUID(), null, null);

        assertThat(inserts).hasValue(1);
    }

    private static AuditService service(AtomicInteger inserts) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("INSERT INTO audit_log_events"), eq(Long.class), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    inserts.incrementAndGet();
                    return 1L;
                });
        return new AuditService(jdbc, new ObjectMapper(), new SimpleMeterRegistry());
    }
}
