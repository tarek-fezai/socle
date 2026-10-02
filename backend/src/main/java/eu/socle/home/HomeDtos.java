// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.home;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class HomeDtos {

    private HomeDtos() {}

    public record HomeResponse(
            String greetingFirstName,
            Kpis kpis,
            List<ResumeItem> resume,
            List<RecentlyPublishedItem> recentlyPublished,
            List<PendingApprovalItem> pendingApprovals,
            List<TeamActivityItem> teamActivity
    ) {}

    public record Kpis(
            long publishedDocuments,
            long pendingApprovals,
            long viewsThisMonth,
            Integer averageReliabilityPercent
    ) {}

    public record ResumeItem(
            UUID documentId,
            String title,
            Instant updatedAt,
            String relativeLabel
    ) {}

    public record RecentlyPublishedItem(
            UUID documentId,
            String title,
            String spaceName,
            Instant publishedAt
    ) {}

    public record PendingApprovalItem(
            UUID documentId,
            UUID requestId,
            String title,
            String requesterName,
            String slaRemainingLabel
    ) {}

    public record TeamActivityItem(
            String eventType,
            String actorDisplayName,
            boolean you,
            String documentTitle,
            UUID documentId,
            Instant createdAt,
            String relativeLabel,
            String actionLabel
    ) {}
}
