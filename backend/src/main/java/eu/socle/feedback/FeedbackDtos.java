// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.feedback;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;

public final class FeedbackDtos {

    private FeedbackDtos() {}

    public record PutFeedbackRequest(@NotNull Boolean helpful) {}

    public record FeedbackTotals(long yes, long no) {}

    /**
     * @param myVote vote de l'utilisateur courant ({@code null} = pas de vote — sérialisé en {@code null})
     * @param totals agrégats oui/non — présents uniquement pour les éditeurs du document (champ omis sinon)
     */
    public record FeedbackView(
            Boolean myVote,
            @JsonInclude(JsonInclude.Include.NON_NULL) FeedbackTotals totals
    ) {}
}
