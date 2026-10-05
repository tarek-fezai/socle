// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import eu.socle.document.DocumentApprovalService.ApprovalStartResponse;
import eu.socle.document.DocumentApprovalService.ApprovalView;
import eu.socle.document.DocumentApprovalService.DecisionRequest;
import eu.socle.document.DocumentDtos.CreateDocumentRequest;
import eu.socle.document.DocumentDtos.DocumentListPage;
import eu.socle.document.DocumentDtos.DocumentResponse;
import eu.socle.document.DocumentDtos.DocumentSummary;
import eu.socle.document.DocumentDtos.UpdateDocumentRequest;
import eu.socle.document.DocumentDtos.VersionDetail;
import eu.socle.document.DocumentDtos.VersionDiffResponse;
import eu.socle.document.DocumentDtos.VersionPage;
import eu.socle.workflowdef.ApprovalWorkflowDefinitionService;
import eu.socle.workflowdef.WorkflowDefinitionDtos.ApplicableView;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/documents", "/api/documents"})
public class DocumentController {

    private final DocumentService service;
    private final DocumentApprovalService approvalService;
    private final ApprovalWorkflowDefinitionService workflowDefinitions;
    private final DocumentViewService documentViewService;

    public DocumentController(
            DocumentService service,
            DocumentApprovalService approvalService,
            ApprovalWorkflowDefinitionService workflowDefinitions,
            DocumentViewService documentViewService
    ) {
        this.service = service;
        this.approvalService = approvalService;
        this.workflowDefinitions = workflowDefinitions;
        this.documentViewService = documentViewService;
    }

    @GetMapping
    public DocumentListPage list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset
    ) {
        return service.list(jwt, limit, offset);
    }

    @GetMapping("/{id}")
    public DocumentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(jwt, id);
    }

    /** Incrémente le compteur de vues agrégé du jour (aucun user_id stocké). */
    @PostMapping("/{id}/view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordView(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        documentViewService.recordView(jwt, id);
    }

    /**
     * Page composite : résout les blocs transclusion (OpenFGA à chaque lecture).
     * {@code Cache-Control: no-store} — le contenu résolu ne doit pas être mis en cache HTTP.
     */
    @GetMapping("/{id}/resolved")
    public ResponseEntity<DocumentResponse> getResolved(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(service.getResolved(jwt, id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateDocumentRequest request
    ) {
        return service.create(jwt, request);
    }

    @PutMapping("/{id}")
    public DocumentResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDocumentRequest request
    ) {
        return service.update(jwt, id, request);
    }

    @PutMapping("/{id}/visibility")
    public DocumentResponse updateVisibility(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody DocumentDtos.UpdateVisibilityRequest request
    ) {
        return service.updateVisibility(jwt, id, request.visibility());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> softDelete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        var r = service.softDelete(jwt, id);
        return Map.of("documents", r.documents(), "folders", r.folders(), "spaces", r.spaces());
    }

    @GetMapping("/{id}/versions")
    public VersionPage listVersions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return service.listVersions(jwt, id, offset, limit);
    }

    @GetMapping("/{id}/versions/{versionNo}")
    public VersionDetail getVersion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable int versionNo
    ) {
        return service.getVersion(jwt, id, versionNo);
    }

    @GetMapping("/{id}/versions/{a}/diff/{b}")
    public VersionDiffResponse diffVersions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable("a") int versionA,
            @PathVariable("b") int versionB
    ) {
        return service.diff(jwt, id, versionA, versionB);
    }

    /**
     * Comparaison Markdown ligne à ligne (écran Historique). Corps stocké, transclusions non
     * résolues. 413 si une version dépasse {@code socle.diff.max-lines}.
     */
    @GetMapping("/{id}/versions/{a}/compare/{b}")
    public DocumentDtos.VersionCompareResponse compareVersions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable("a") int versionA,
            @PathVariable("b") int versionB,
            @RequestParam(defaultValue = "lines") String mode
    ) {
        return service.compare(jwt, id, versionA, versionB, mode);
    }

    @PostMapping("/{id}/versions/{versionNo}/restore")
    public DocumentResponse restoreVersion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable int versionNo,
            @RequestParam(required = false) Integer expectedVersionNo
    ) {
        return service.restore(jwt, id, versionNo, expectedVersionNo);
    }

    @PostMapping("/{id}/approvals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApprovalStartResponse requestApproval(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        return approvalService.startApproval(jwt, id);
    }

    @GetMapping("/{id}/approvals/current")
    public ResponseEntity<ApprovalView> currentApproval(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        ApprovalView view = approvalService.currentApproval(jwt, id);
        return view == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(view);
    }

    /** Aperçu du workflow qui s'appliquera à la prochaine soumission (scope espace/type). */
    @GetMapping("/{id}/approvals/applicable-workflow")
    public ApplicableView applicableWorkflow(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        DocumentResponse doc = service.get(jwt, id);
        return workflowDefinitions.resolveApplicable(doc.spaceId(), doc.docType());
    }

    @PostMapping("/{id}/approvals/{requestId}/decide")
    public Map<String, String> decide(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable UUID requestId,
            @Valid @RequestBody DecisionRequest body
    ) {
        return approvalService.decide(jwt, id, requestId, body);
    }
}
