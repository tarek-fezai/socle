// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import eu.socle.document.DocumentApprovalService.ApprovalDetailView;
import eu.socle.document.DocumentApprovalService.ApprovalView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/approvals")
@ConditionalOnProperty(name = "socle.temporal.enabled", havingValue = "true", matchIfMissing = true)
public class ApprovalController {

    private final DocumentApprovalService approvalService;

    public ApprovalController(DocumentApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping("/mine")
    public List<ApprovalView> mine(@AuthenticationPrincipal Jwt jwt) {
        return approvalService.listMine(jwt);
    }

    /** Détail pour tout viewer du document ; décision réservée si {@code canDecide}. */
    @GetMapping("/{requestId}")
    public ApprovalDetailView get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId
    ) {
        return approvalService.getApproval(jwt, requestId);
    }
}
