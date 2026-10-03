// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Calcul unique du {@code documents.reliability_score}.
 * Uniquement pour {@code status = 'valide'} ; hors valide → score NULL.
 *
 * <p>Source de fraîcheur retenue : {@code MAX(created_at)} des événements
 * {@code document.approved} dans {@code audit_log_events} — trace canonique de
 * validation, plus fiable que {@code document_versions.created_at} (qui archive
 * aussi les mutations hors validation).
 */
@Service
public class ReliabilityScoreService {

    private static final Logger log = LoggerFactory.getLogger(ReliabilityScoreService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ReliabilityScoreProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    public ReliabilityScoreService(
            JdbcTemplate jdbcTemplate,
            ReliabilityScoreProperties properties,
            Clock clock,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Entrées de la formule — testable unitairement sans base.
     */
    public record ScoreInputs(
            Instant lastApprovedAt,
            int reviewCycleDays,
            int totalComments,
            int resolvedComments,
            boolean mandatoryAck,
            boolean hasActiveCampaign,
            int acknowledgmentsCount,
            int audienceSize,
            LocalDate campaignDueDate,
            Instant now
    ) {}

    public record ScoreBreakdown(
            double freshness,
            double resolution,
            double attestation,
            BigDecimal score
    ) {}

    /** Publié pendant la TX d'approbation ; traité après commit, en async. */
    public record RecalculationRequested(UUID documentId) {}

    /**
     * Formule pure : {@code round(0.4×freshness + 0.3×resolution + 0.3×attestation, 2)}.
     */
    public ScoreBreakdown compute(ScoreInputs in) {
        double freshness = computeFreshness(in.lastApprovedAt(), in.reviewCycleDays(), in.now());
        double resolution = computeResolution(in.totalComments(), in.resolvedComments());
        double attestation = computeAttestation(
                in.mandatoryAck(),
                in.hasActiveCampaign(),
                in.acknowledgmentsCount(),
                in.audienceSize(),
                in.campaignDueDate(),
                in.now());
        BigDecimal score = BigDecimal.valueOf(
                        ReliabilityScoreDefaults.WEIGHT_FRESHNESS * freshness
                                + ReliabilityScoreDefaults.WEIGHT_RESOLUTION * resolution
                                + ReliabilityScoreDefaults.WEIGHT_ATTESTATION * attestation)
                .setScale(2, RoundingMode.HALF_UP);
        return new ScoreBreakdown(freshness, resolution, attestation, score);
    }

    static double computeFreshness(Instant lastApprovedAt, int reviewCycleDays, Instant now) {
        int cycle = Math.max(reviewCycleDays, 1);
        if (lastApprovedAt == null) {
            return 0.0;
        }
        long days = ChronoUnit.DAYS.between(
                lastApprovedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                now.atZone(java.time.ZoneOffset.UTC).toLocalDate());
        if (days < 0) {
            days = 0;
        }
        double ratio = 1.0 - ((double) days / (double) cycle);
        return clamp01(ratio) * 100.0;
    }

    static double computeResolution(int totalComments, int resolvedComments) {
        if (totalComments <= 0) {
            return 100.0;
        }
        int resolved = Math.min(Math.max(resolvedComments, 0), totalComments);
        return (resolved * 100.0) / totalComments;
    }

    static double computeAttestation(
            boolean mandatoryAck,
            boolean hasActiveCampaign,
            int acknowledgmentsCount,
            int audienceSize,
            LocalDate campaignDueDate,
            Instant now
    ) {
        if (!mandatoryAck || !hasActiveCampaign) {
            return 100.0;
        }
        if (audienceSize <= 0) {
            return 100.0;
        }
        double attestation = (Math.min(acknowledgmentsCount, audienceSize) * 100.0) / audienceSize;
        LocalDate today = now.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        if (campaignDueDate != null && today.isAfter(campaignDueDate) && attestation < 100.0) {
            attestation = attestation * 0.5;
        }
        return attestation;
    }

    private static double clamp01(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    /**
     * Recalcule et persiste si le document est {@code valide} ; sinon efface le score.
     */
    @Transactional
    public void recalculate(UUID documentId) {
        DocumentRow row = loadDocument(documentId);
        if (row == null) {
            log.warn("Recalcul fiabilité : document {} introuvable", documentId);
            return;
        }
        if (!"valide".equals(row.status())) {
            clearScoreInDb(documentId);
            return;
        }

        Instant lastApprovedAt = loadLastApprovedAt(documentId);
        int cycleDays = resolveReviewCycleDays(row.spaceId(), documentId);
        CommentCounts comments = loadCommentCounts(documentId);
        CampaignInfo campaign = loadActiveCampaign(documentId);

        ScoreBreakdown breakdown = compute(new ScoreInputs(
                lastApprovedAt,
                cycleDays,
                comments.total(),
                comments.resolved(),
                row.mandatoryAck(),
                campaign != null,
                campaign != null ? campaign.ackCount() : 0,
                campaign != null ? campaign.audienceSize() : 0,
                campaign != null ? campaign.dueDate() : null,
                clock.instant()
        ));

        Instant computedAt = clock.instant();
        jdbcTemplate.update("""
                UPDATE documents
                   SET reliability_score = ?,
                       reliability_computed_at = ?
                 WHERE id = ?
                """,
                breakdown.score(),
                Timestamp.from(computedAt),
                documentId);
        log.debug("reliability_score document={} score={} freshness={} resolution={} attestation={}",
                documentId, breakdown.score(), breakdown.freshness(),
                breakdown.resolution(), breakdown.attestation());
    }

    /** Recalcule uniquement si le document est encore {@code valide}. */
    @Transactional
    public void recalculateIfValide(UUID documentId) {
        String status = jdbcTemplate.query(
                "SELECT status FROM documents WHERE id = ? AND deleted_at IS NULL",
                rs -> rs.next() ? rs.getString(1) : null,
                documentId);
        if ("valide".equals(status)) {
            recalculate(documentId);
        }
    }

    /**
     * Hook commentaires : appeler après ajout ou résolution sur {@code document_comments}.
     */
    @Transactional
    public void onDocumentCommentChanged(UUID documentId) {
        recalculateIfValide(documentId);
    }

    /**
     * Hook attestations : appeler après INSERT dans {@code attestation_acknowledgments}.
     */
    @Transactional
    public void onAttestationAcknowledged(UUID documentId) {
        recalculateIfValide(documentId);
    }

    /**
     * Efface le score en mémoire (JPA) — transition {@code valide → en_revue} immédiate.
     */
    public void clearScoreOnEntity(DocumentEntity entity) {
        entity.setReliabilityScore(null);
        entity.setReliabilityComputedAt(null);
    }

    @Transactional
    public void clearScoreInDb(UUID documentId) {
        jdbcTemplate.update("""
                UPDATE documents
                   SET reliability_score = NULL,
                       reliability_computed_at = NULL
                 WHERE id = ?
                """,
                documentId);
    }

    /**
     * Déclenche un recalcul après commit de la transaction courante, en asynchrone
     * (chemin d'approbation non bloqué). Publie un événement (évite l'auto-invocation @Async).
     */
    public void requestRecalculationAfterCommit(UUID documentId) {
        eventPublisher.publishEvent(new RecalculationRequested(documentId));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRecalculationRequested(RecalculationRequested event) {
        try {
            recalculate(event.documentId());
        } catch (Exception e) {
            log.error("Échec recalcul async reliability_score document={}", event.documentId(), e);
        }
    }

    /**
     * Job quotidien : recalcule les documents {@code valide} dont le score a plus de 24h
     * (ou jamais calculé).
     */
    @Transactional
    public int recalculateStaleValideDocuments() {
        Instant threshold = clock.instant()
                .minus(Duration.ofHours(ReliabilityScoreDefaults.STALE_AFTER_HOURS));
        List<UUID> ids = jdbcTemplate.query(
                """
                SELECT id FROM documents
                 WHERE status = 'valide'
                   AND deleted_at IS NULL
                   AND (reliability_computed_at IS NULL OR reliability_computed_at < ?)
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                Timestamp.from(threshold));
        int count = 0;
        for (UUID id : ids) {
            recalculate(id);
            count++;
        }
        return count;
    }

    private DocumentRow loadDocument(UUID documentId) {
        List<DocumentRow> rows = jdbcTemplate.query(
                """
                SELECT id, space_id, status, is_mandatory_ack
                  FROM documents WHERE id = ? AND deleted_at IS NULL
                """,
                (rs, i) -> new DocumentRow(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("space_id"),
                        rs.getString("status"),
                        rs.getBoolean("is_mandatory_ack")
                ),
                documentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * Dernière validation : {@code audit_log_events.action = document.approved}.
     */
    Instant loadLastApprovedAt(UUID documentId) {
        List<Timestamp> ts = jdbcTemplate.query(
                """
                SELECT MAX(created_at) AS last_approved
                  FROM audit_log_events
                 WHERE resource_type = 'document'
                   AND resource_id = ?
                   AND action = ?
                """,
                (rs, i) -> rs.getTimestamp("last_approved"),
                documentId,
                eu.socle.audit.AuditActions.DOCUMENT_APPROVED);
        if (ts.isEmpty() || ts.getFirst() == null) {
            return null;
        }
        return ts.getFirst().toInstant();
    }

    int resolveReviewCycleDays(UUID spaceId, UUID documentId) {
        List<Integer> days = jdbcTemplate.query(
                """
                SELECT retention_days
                  FROM retention_policies
                 WHERE (applies_to = 'space' AND scope_ref_id = ?)
                    OR (applies_to = 'tag' AND scope_ref_id IN (
                            SELECT tag_id FROM document_tags WHERE document_id = ?
                       ))
                 ORDER BY created_at ASC
                 LIMIT 1
                """,
                (rs, i) -> rs.getInt("retention_days"),
                spaceId,
                documentId);
        if (days.isEmpty()) {
            return properties.getDefaultReviewCycleDays();
        }
        return days.getFirst();
    }

    private CommentCounts loadCommentCounts(UUID documentId) {
        return jdbcTemplate.query(
                """
                SELECT COUNT(*)::int AS total,
                       COUNT(*) FILTER (WHERE resolved = true)::int AS resolved
                  FROM document_comments
                 WHERE document_id = ?
                   AND deleted_at IS NULL
                   AND parent_comment_id IS NULL
                """,
                rs -> {
                    rs.next();
                    return new CommentCounts(rs.getInt("total"), rs.getInt("resolved"));
                },
                documentId);
    }

    /**
     * Campagne active = la campagne ouverte ({@code closed_at IS NULL}) du document (au plus une).
     * Audience = {@code audience_size} figée à la création (Y) ; accusés = lignes de la campagne (X).
     * {@code due_date} de la campagne = échéance d'attestation.
     */
    private CampaignInfo loadActiveCampaign(UUID documentId) {
        List<CampaignInfo> campaigns = jdbcTemplate.query(
                """
                SELECT c.id, c.due_date, c.audience_size,
                       (SELECT COUNT(*)::int FROM attestation_acknowledgments a
                         WHERE a.campaign_id = c.id) AS ack_count
                  FROM attestation_campaigns c
                 WHERE c.document_id = ?
                   AND c.closed_at IS NULL
                 ORDER BY c.created_at DESC
                 LIMIT 1
                """,
                (rs, i) -> {
                    Date due = rs.getDate("due_date");
                    return new CampaignInfo(
                            (UUID) rs.getObject("id"),
                            due != null ? due.toLocalDate() : null,
                            rs.getInt("ack_count"),
                            rs.getInt("audience_size")
                    );
                },
                documentId);
        return campaigns.isEmpty() ? null : campaigns.getFirst();
    }

    private record DocumentRow(UUID id, UUID spaceId, String status, boolean mandatoryAck) {}
    private record CommentCounts(int total, int resolved) {}
    private record CampaignInfo(UUID id, LocalDate dueDate, int ackCount, int audienceSize) {}
}
