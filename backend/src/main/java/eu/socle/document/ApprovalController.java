package eu.socle.document;

import eu.socle.document.DocumentApprovalService.ApprovalView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
}
