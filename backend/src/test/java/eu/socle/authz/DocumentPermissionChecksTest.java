// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.authz;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import dev.openfga.sdk.api.client.model.ClientBatchCheckResponse;
import dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse;
import eu.socle.authz.AuthorizationService.DocumentPermissionChecks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link AuthorizationService#batchCheckDocumentPermissions} : un seul BatchCheck de 5 relations. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentPermissionChecksTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock OpenFgaClient openFgaClient;

    AuthorizationService service;
    List<ClientBatchCheckRequest> requests;

    @BeforeEach
    void setUp() {
        service = new AuthorizationService(openFgaClient);
        requests = new ArrayList<>();
    }

    /** Autorise exactement les couples « relation objet » donnés. */
    private void allow(Set<String> relationObjects) throws Exception {
        when(openFgaClient.batchCheck(any(ClientBatchCheckRequest.class))).thenAnswer(inv -> {
            ClientBatchCheckRequest req = inv.getArgument(0);
            requests.add(req);
            List<ClientBatchCheckSingleResponse> results = new ArrayList<>();
            for (ClientBatchCheckItem item : req.getChecks()) {
                boolean allowed = relationObjects.contains(item.getRelation() + " " + item.getObject());
                results.add(new ClientBatchCheckSingleResponse(
                        allowed, item, item.getCorrelationId(), null));
            }
            return CompletableFuture.completedFuture(new ClientBatchCheckResponse(results));
        });
    }

    @Test
    void viewer_hasOnlyViewerRelations_inOneBatchCheck() throws Exception {
        allow(Set.of("viewer document:" + DOC, "viewer space:" + SPACE));

        DocumentPermissionChecks c = service.batchCheckDocumentPermissions(USER, DOC, SPACE);

        assertThat(c).isEqualTo(new DocumentPermissionChecks(false, false, true, false, true));
        verify(openFgaClient, times(1)).batchCheck(any(ClientBatchCheckRequest.class));
        verify(openFgaClient, never()).check(any());
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().getChecks()).hasSize(5);
    }

    @Test
    void editor_hasEditorAndViewer() throws Exception {
        allow(Set.of(
                "editor document:" + DOC, "viewer document:" + DOC, "viewer space:" + SPACE));

        DocumentPermissionChecks c = service.batchCheckDocumentPermissions(USER, DOC, SPACE);

        assertThat(c.documentEditor()).isTrue();
        assertThat(c.documentOwner()).isFalse();
        assertThat(c.spaceOwner()).isFalse();
    }

    @Test
    void spaceOwner_hasOwnerRelations() throws Exception {
        allow(Set.of(
                "owner document:" + DOC, "editor document:" + DOC, "viewer document:" + DOC,
                "owner space:" + SPACE, "viewer space:" + SPACE));

        DocumentPermissionChecks c = service.batchCheckDocumentPermissions(USER, DOC, SPACE);

        assertThat(c).isEqualTo(new DocumentPermissionChecks(true, true, true, true, true));
    }
}
