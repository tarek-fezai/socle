// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import io.temporal.testing.WorkflowReplayer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Rejeu d'un historique {@link DocumentApprovalWorkflow} capturé sous temporal-sdk 1.27.x.
 * Doit rester vert après montée SDK (compatibilité de rejeu).
 */
class DocumentApprovalWorkflowReplayTest {

    @Test
    void replayHistoryCapturedWithSdk1_27() throws Exception {
        String json;
        try (InputStream in = DocumentApprovalWorkflowReplayTest.class
                .getResourceAsStream("/temporal/document-approval-history-1.27.json")) {
            if (in == null) {
                throw new IllegalStateException("missing history resource");
            }
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        WorkflowReplayer.replayWorkflowExecution(json, DocumentApprovalWorkflowImpl.class);
    }
}
