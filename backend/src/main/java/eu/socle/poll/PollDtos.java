// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.poll;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PollDtos {

    private PollDtos() {}

    public record PollOptionResult(String option, long count) {}

    /**
     * Vue lecteur : totaux agrégés + éventuel vote propre.
     * Aucune liste nominative de votants.
     */
    public record PollView(
            UUID id,
            UUID documentId,
            String question,
            List<String> options,
            boolean closed,
            Instant closedAt,
            String myVote,
            List<PollOptionResult> results,
            long totalVotes
    ) {}

    public record VoteRequest(@NotBlank String option) {}

    public record PersonalPollVoteExport(
            @NotNull UUID pollId,
            @NotNull UUID documentId,
            String question,
            String option,
            Instant updatedAt
    ) {}
}
