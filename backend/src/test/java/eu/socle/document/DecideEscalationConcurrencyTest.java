package eu.socle.document;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrence decide ↔ escalade sur le même verrou ligne ({@code FOR UPDATE}).
 * Un seul gagne de façon déterministe — jamais les deux appliqués.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class DecideEscalationConcurrencyTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;
    static TransactionTemplate tx;

    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID WF = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID REQ = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY, email TEXT NOT NULL, display_name TEXT NOT NULL, status TEXT NOT NULL
                )
                """);
        jdbc.execute("CREATE TABLE spaces (id UUID PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, space_id UUID NOT NULL REFERENCES spaces(id),
                  title TEXT NOT NULL, body JSONB NOT NULL DEFAULT '{}', status TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE approval_workflows (
                  id UUID PRIMARY KEY, name TEXT NOT NULL, status TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE approval_requests (
                  id UUID PRIMARY KEY,
                  document_id UUID NOT NULL REFERENCES documents(id),
                  workflow_id UUID NOT NULL REFERENCES approval_workflows(id),
                  temporal_workflow_id TEXT NOT NULL UNIQUE,
                  requested_by UUID NOT NULL REFERENCES users(id),
                  current_step_order INTEGER NOT NULL DEFAULT 1,
                  status TEXT NOT NULL DEFAULT 'en_cours',
                  sla_deadline_at TIMESTAMPTZ,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);

        jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?,?,?,?)",
                USER, "u@example.com", "U", "active");
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?,?)", SPACE, "S");
        jdbc.update("INSERT INTO documents (id, space_id, title, status) VALUES (?,?,?,?)",
                DOC, SPACE, "Doc", "en_revue");
        jdbc.update("INSERT INTO approval_workflows (id, name, status) VALUES (?,?,?)",
                WF, "W", "active");
    }

    @Test
    void decideHoldsLock_escalationWaitsThenSeesAdvancedOrResolved_onlyOneWins() throws Exception {
        resetRequest(1, "en_cours");

        CountDownLatch decideLocked = new CountDownLatch(1);
        CountDownLatch allowDecideCommit = new CountDownLatch(1);
        AtomicInteger decideSignals = new AtomicInteger();
        AtomicReference<String> escalationResult = new AtomicReference<>();
        AtomicReference<String> decideResult = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> decideFuture = pool.submit(() -> tx.executeWithoutResult(status -> {
                Map<String, Object> row = jdbc.queryForMap("""
                        SELECT status, current_step_order FROM approval_requests WHERE id = ? FOR UPDATE
                        """, REQ);
                assertThat(row.get("status")).isEqualTo("en_cours");
                assertThat(((Number) row.get("current_step_order")).intValue()).isEqualTo(1);
                decideLocked.countDown();
                try {
                    assertThat(allowDecideCommit.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                // Simule le signal Temporal sous verrou, puis clôture (decide gagne)
                decideSignals.incrementAndGet();
                jdbc.update("UPDATE approval_requests SET status = 'approuve' WHERE id = ?", REQ);
                decideResult.set("decide_won");
            }));

            Future<?> escalationFuture = pool.submit(() -> {
                try {
                    assertThat(decideLocked.await(5, TimeUnit.SECONDS)).isTrue();
                    // Lance l'escalade pendant que decide tient le FOR UPDATE
                    String result = tx.execute(status -> {
                        Map<String, Object> row = jdbc.queryForMap("""
                                SELECT status, current_step_order FROM approval_requests WHERE id = ? FOR UPDATE
                                """, REQ);
                        if (!"en_cours".equals(row.get("status"))) {
                            return "noop_resolved";
                        }
                        int step = ((Number) row.get("current_step_order")).intValue();
                        if (step != 1) {
                            return "noop_step_mismatch";
                        }
                        jdbc.update("""
                                UPDATE approval_requests
                                   SET current_step_order = 2
                                 WHERE id = ? AND status = 'en_cours' AND current_step_order = 1
                                """, REQ);
                        return "escalade_2";
                    });
                    escalationResult.set(result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });

            // Laisse l'escalade bloquer sur le verrou, puis commit decide
            Thread.sleep(200);
            allowDecideCommit.countDown();

            decideFuture.get(10, TimeUnit.SECONDS);
            escalationFuture.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(decideResult.get()).isEqualTo("decide_won");
        assertThat(decideSignals.get()).isEqualTo(1);
        // Escalade a attendu puis vu status résolu → noop, pas d'étape 2
        assertThat(escalationResult.get()).isEqualTo("noop_resolved");

        Map<String, Object> finalRow = jdbc.queryForMap(
                "SELECT status, current_step_order FROM approval_requests WHERE id = ?", REQ);
        assertThat(finalRow.get("status")).isEqualTo("approuve");
        assertThat(((Number) finalRow.get("current_step_order")).intValue()).isEqualTo(1);
    }

    @Test
    void escalationFirst_decideWithObsoleteStepSeesMismatch() throws Exception {
        resetRequest(1, "en_cours");

        CountDownLatch escalationLocked = new CountDownLatch(1);
        CountDownLatch allowEscalationCommit = new CountDownLatch(1);
        AtomicReference<String> decideError = new AtomicReference<>();
        AtomicBoolean signalWouldHaveBeenSent = new AtomicBoolean(false);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> escFuture = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForMap("SELECT id FROM approval_requests WHERE id = ? FOR UPDATE", REQ);
                escalationLocked.countDown();
                try {
                    assertThat(allowEscalationCommit.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                jdbc.update("""
                        UPDATE approval_requests
                           SET current_step_order = 2
                         WHERE id = ? AND status = 'en_cours' AND current_step_order = 1
                        """, REQ);
            }));

            Future<?> decideFuture = pool.submit(() -> {
                try {
                    assertThat(escalationLocked.await(5, TimeUnit.SECONDS)).isTrue();
                    tx.executeWithoutResult(status -> {
                        Map<String, Object> row = jdbc.queryForMap("""
                                SELECT status, current_step_order FROM approval_requests WHERE id = ? FOR UPDATE
                                """, REQ);
                        if (!"en_cours".equals(row.get("status"))) {
                            decideError.set(ApprovalConflictException.ALREADY_RESOLVED);
                            return;
                        }
                        int actual = ((Number) row.get("current_step_order")).intValue();
                        int expected = 1;
                        if (actual != expected) {
                            decideError.set(ApprovalConflictException.STEP_ADVANCED);
                            return;
                        }
                        signalWouldHaveBeenSent.set(true);
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });

            Thread.sleep(200);
            allowEscalationCommit.countDown();
            escFuture.get(10, TimeUnit.SECONDS);
            decideFuture.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(decideError.get()).isEqualTo(ApprovalConflictException.STEP_ADVANCED);
        assertThat(signalWouldHaveBeenSent).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT current_step_order FROM approval_requests WHERE id = ?", Integer.class, REQ))
                .isEqualTo(2);
    }

    private static void resetRequest(int step, String status) {
        jdbc.update("DELETE FROM approval_requests WHERE id = ?", REQ);
        jdbc.update("""
                INSERT INTO approval_requests
                  (id, document_id, workflow_id, temporal_workflow_id, requested_by,
                   current_step_order, status, created_at)
                VALUES (?, ?, ?, 'wf-conc', ?, ?, ?, now())
                """,
                REQ, DOC, WF, USER, step, status);
    }
}
