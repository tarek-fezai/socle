package eu.socle.storage;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diagnostic admin — dérive projection Postgres vs blob Git HEAD (lecture seule).
 */
@RestController
@RequestMapping("/api/v1/admin/storage")
public class GitProjectionDriftController {

    private final GitProjectionDriftService driftService;

    public GitProjectionDriftController(GitProjectionDriftService driftService) {
        this.driftService = driftService;
    }

    /**
     * Liste les documents dont {@code documents.body} contient des transclusions
     * absentes du blob Git HEAD. Ne corrige rien.
     */
    @GetMapping("/git-projection-drift")
    public GitProjectionDriftService.DriftReport gitProjectionDrift() {
        return driftService.listMissingTransclusionsInGitHead();
    }
}
