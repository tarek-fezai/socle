// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.attestation;

import eu.socle.attestation.AttestationDtos.AcknowledgmentView;
import eu.socle.attestation.AttestationDtos.ActiveAttestation;
import eu.socle.attestation.AttestationDtos.CampaignView;
import eu.socle.attestation.AttestationDtos.CreateCampaignRequest;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import eu.socle.document.ReliabilityScoreService;
import eu.socle.user.UserSyncService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Campagnes d'attestation de lecture sur un document.
 *
 * <ul>
 *   <li>Création / clôture / liste nominative : owners de l'espace du document uniquement.</li>
 *   <li>Audience figée à la création ({@code audience_size} = Y) ; au plus une campagne ouverte par document.</li>
 *   <li>Accusé : utilisateur de l'audience, version courante du document enregistrée.</li>
 * </ul>
 * Un document inaccessible (non {@code viewer}) répond 404 — existence non révélée.
 */
@Service
public class AttestationService {

    public static final String AUDIENCE_SPACE_MEMBERS = "space_members";
    public static final String AUDIENCE_GROUP = "group";

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final DocumentRepository documentRepository;
    private final AuditService auditService;
    private final ReliabilityScoreService reliabilityScoreService;
    private final Clock clock;

    public AttestationService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            DocumentRepository documentRepository,
            AuditService auditService,
            ReliabilityScoreService reliabilityScoreService,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.documentRepository = documentRepository;
        this.auditService = auditService;
        this.reliabilityScoreService = reliabilityScoreService;
        this.clock = clock;
    }

    @Transactional
    public CampaignView create(Jwt jwt, UUID documentId, CreateCampaignRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        DocumentEntity doc = requireViewableDocument(user.getId(), documentId);
        requireSpaceOwner(user.getId(), doc);

        String type = request.audienceType() == null ? "" : request.audienceType().trim();
        UUID audienceRef;
        int audienceSize;
        switch (type) {
            case AUDIENCE_SPACE_MEMBERS -> {
                audienceRef = null;
                audienceSize = authorizationService.listSpaceMemberIds(doc.getSpaceId()).size();
            }
            case AUDIENCE_GROUP -> {
                audienceRef = request.audienceRef();
                if (audienceRef == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "audience_ref requis pour un groupe");
                }
                Integer exists = jdbc.queryForObject(
                        "SELECT count(*) FROM groups WHERE id = ?", Integer.class, audienceRef);
                if (exists == null || exists == 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Groupe introuvable");
                }
                Integer size = jdbc.queryForObject(
                        "SELECT count(*) FROM group_members WHERE group_id = ?", Integer.class, audienceRef);
                audienceSize = size == null ? 0 : size;
            }
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "audience_type doit être space_members ou group");
        }

        LocalDate dueDate = request.dueDate();
        Instant now = clock.instant();
        if (dueDate != null && dueDate.isBefore(LocalDate.ofInstant(now, ZoneOffset.UTC))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "due_date dans le passé");
        }

        if (findOpenCampaign(documentId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Une campagne est déjà ouverte pour ce document");
        }

        UUID campaignId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO attestation_campaigns
                      (id, document_id, version_no, audience_type, audience_ref, audience_size,
                       due_date, created_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    campaignId, documentId, doc.getCurrentVersionNo(), type, audienceRef, audienceSize,
                    dueDate == null ? null : Date.valueOf(dueDate), user.getId(), Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Une campagne est déjà ouverte pour ce document");
        }
        jdbc.update("""
                UPDATE documents SET is_mandatory_ack = true, ack_due_date = ? WHERE id = ?
                """,
                dueDate == null ? null : Date.valueOf(dueDate), documentId);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("campaignId", campaignId.toString());
        meta.put("versionNo", doc.getCurrentVersionNo());
        meta.put("audienceType", type);
        if (audienceRef != null) {
            meta.put("audienceRef", audienceRef.toString());
        }
        meta.put("audienceSize", audienceSize);
        if (dueDate != null) {
            meta.put("dueDate", dueDate.toString());
        }
        auditService.recordSync(user.getId(), false, AuditActions.ATTESTATION_CAMPAIGN_CREATED,
                "document", documentId, meta, null);
        reliabilityScoreService.recalculateIfValide(documentId);

        return new CampaignView(
                campaignId, documentId, doc.getCurrentVersionNo(), type, audienceRef, audienceSize,
                dueDate, user.getId(), now, null);
    }

    @Transactional
    public void close(Jwt jwt, UUID documentId, UUID campaignId) {
        var user = userSyncService.syncFromJwt(jwt);
        DocumentEntity doc = requireViewableDocument(user.getId(), documentId);
        requireSpaceOwner(user.getId(), doc);
        CampaignRow campaign = requireCampaign(documentId, campaignId);
        if (campaign.closedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Campagne déjà clôturée");
        }

        int closed = jdbc.update(
                "UPDATE attestation_campaigns SET closed_at = ? WHERE id = ? AND closed_at IS NULL",
                Timestamp.from(clock.instant()), campaignId);
        if (closed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Campagne déjà clôturée");
        }
        jdbc.update("UPDATE documents SET is_mandatory_ack = false, ack_due_date = NULL WHERE id = ?",
                documentId);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("campaignId", campaignId.toString());
        meta.put("ackCount", countAcknowledgments(campaignId));
        meta.put("audienceSize", campaign.audienceSize());
        auditService.recordSync(user.getId(), false, AuditActions.ATTESTATION_CAMPAIGN_CLOSED,
                "document", documentId, meta, null);
        reliabilityScoreService.recalculateIfValide(documentId);
    }

    /**
     * Campagne ouverte qui concerne l'utilisateur courant, ou vide (aucune campagne,
     * ou utilisateur hors audience).
     */
    @Transactional(readOnly = true)
    public Optional<ActiveAttestation> active(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);
        DocumentEntity doc = requireViewableDocument(user.getId(), documentId);
        return findOpenCampaign(documentId)
                .filter(c -> isInAudience(user.getId(), doc, c))
                .map(c -> toActive(user.getId(), doc, c));
    }

    @Transactional
    public ActiveAttestation acknowledge(Jwt jwt, UUID documentId, UUID campaignId) {
        var user = userSyncService.syncFromJwt(jwt);
        DocumentEntity doc = requireViewableDocument(user.getId(), documentId);
        CampaignRow campaign = requireCampaign(documentId, campaignId);
        if (campaign.closedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Campagne clôturée");
        }
        if (!isInAudience(user.getId(), doc, campaign)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Vous n'êtes pas concerné par cette campagne");
        }

        int inserted = jdbc.update("""
                INSERT INTO attestation_acknowledgments (campaign_id, user_id, acknowledged_at, version_no)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (campaign_id, user_id) DO NOTHING
                """,
                campaignId, user.getId(), Timestamp.from(clock.instant()), doc.getCurrentVersionNo());

        if (inserted > 0) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("campaignId", campaignId.toString());
            meta.put("versionNo", doc.getCurrentVersionNo());
            auditService.recordSync(user.getId(), false, AuditActions.ATTESTATION_ACKNOWLEDGED,
                    "document", documentId, meta, null);
            reliabilityScoreService.onAttestationAcknowledged(documentId);
        }
        return toActive(user.getId(), doc, campaign);
    }

    /** Liste nominative des accusés — owners de l'espace uniquement. */
    @Transactional(readOnly = true)
    public List<AcknowledgmentView> acknowledgments(Jwt jwt, UUID documentId, UUID campaignId) {
        var user = userSyncService.syncFromJwt(jwt);
        DocumentEntity doc = requireViewableDocument(user.getId(), documentId);
        requireSpaceOwner(user.getId(), doc);
        requireCampaign(documentId, campaignId);
        return jdbc.query("""
                SELECT a.user_id, u.display_name, u.email, a.acknowledged_at, a.version_no
                  FROM attestation_acknowledgments a
                  JOIN users u ON u.id = a.user_id
                 WHERE a.campaign_id = ?
                 ORDER BY a.acknowledged_at ASC, u.display_name ASC
                """,
                (rs, i) -> new AcknowledgmentView(
                        (UUID) rs.getObject("user_id"),
                        rs.getString("display_name"),
                        rs.getString("email"),
                        rs.getTimestamp("acknowledged_at").toInstant(),
                        rs.getInt("version_no")),
                campaignId);
    }

    // ------------------------------------------------------------------ helpers

    private DocumentEntity requireViewableDocument(UUID userId, UUID documentId) {
        DocumentEntity doc = documentRepository.findActiveById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        if (!authorizationService.hasRelation(userId, "document", documentId, "viewer")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable");
        }
        return doc;
    }

    private void requireSpaceOwner(UUID userId, DocumentEntity doc) {
        if (!authorizationService.hasRelation(userId, "space", doc.getSpaceId(), "owner")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Réservé aux owners de l'espace");
        }
    }

    private boolean isInAudience(UUID userId, DocumentEntity doc, CampaignRow campaign) {
        if (AUDIENCE_GROUP.equals(campaign.audienceType())) {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM group_members WHERE group_id = ? AND user_id = ?",
                    Integer.class, campaign.audienceRef(), userId);
            return n != null && n > 0;
        }
        return authorizationService.hasRelation(userId, "space", doc.getSpaceId(), "viewer");
    }

    private ActiveAttestation toActive(UUID userId, DocumentEntity doc, CampaignRow c) {
        record Ack(Instant at, int versionNo) {}
        Ack ack = jdbc.query("""
                SELECT acknowledged_at, version_no
                  FROM attestation_acknowledgments
                 WHERE campaign_id = ? AND user_id = ?
                """,
                rs -> rs.next()
                        ? new Ack(rs.getTimestamp("acknowledged_at").toInstant(), rs.getInt("version_no"))
                        : null,
                c.id(), userId);
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        boolean overdue = c.dueDate() != null && today.isAfter(c.dueDate()) && c.closedAt() == null;
        return new ActiveAttestation(
                c.id(), c.documentId(), c.versionNo(), doc.getCurrentVersionNo(),
                c.audienceType(), c.audienceRef(), c.dueDate(), overdue,
                ack != null, ack == null ? null : ack.at(), ack == null ? null : ack.versionNo(),
                countAcknowledgments(c.id()), c.audienceSize(), c.createdAt());
    }

    private int countAcknowledgments(UUID campaignId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM attestation_acknowledgments WHERE campaign_id = ?",
                Integer.class, campaignId);
        return n == null ? 0 : n;
    }

    private Optional<CampaignRow> findOpenCampaign(UUID documentId) {
        return jdbc.query(SELECT_CAMPAIGN + " WHERE document_id = ? AND closed_at IS NULL",
                (rs, i) -> mapCampaign(rs), documentId).stream().findFirst();
    }

    private CampaignRow requireCampaign(UUID documentId, UUID campaignId) {
        return jdbc.query(SELECT_CAMPAIGN + " WHERE id = ? AND document_id = ?",
                        (rs, i) -> mapCampaign(rs), campaignId, documentId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campagne introuvable"));
    }

    private static final String SELECT_CAMPAIGN = """
            SELECT id, document_id, version_no, audience_type, audience_ref, audience_size,
                   due_date, created_by, created_at, closed_at
              FROM attestation_campaigns
            """;

    private static CampaignRow mapCampaign(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date due = rs.getDate("due_date");
        Timestamp closed = rs.getTimestamp("closed_at");
        return new CampaignRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("document_id"),
                rs.getInt("version_no"),
                rs.getString("audience_type"),
                (UUID) rs.getObject("audience_ref"),
                rs.getInt("audience_size"),
                due == null ? null : due.toLocalDate(),
                (UUID) rs.getObject("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                closed == null ? null : closed.toInstant());
    }

    private record CampaignRow(
            UUID id,
            UUID documentId,
            int versionNo,
            String audienceType,
            UUID audienceRef,
            int audienceSize,
            LocalDate dueDate,
            UUID createdBy,
            Instant createdAt,
            Instant closedAt
    ) {}
}
