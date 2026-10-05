// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.config;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientCreateStoreResponse;
import dev.openfga.sdk.api.client.model.ClientListStoresResponse;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenFgaBootstrapTest {

    @TempDir
    Path stateDir;

    OpenFgaClient client;
    SocleProperties properties;

    @BeforeEach
    void setUp() {
        client = mock(OpenFgaClient.class);
        properties = new SocleProperties(
                new SocleProperties.Temporal("localhost:7233", "default"),
                new SocleProperties.OpenFga(
                        "http://openfga:8080",
                        "",
                        "",
                        1000,
                        false,
                        50,
                        4,
                        500
                ),
                new SocleProperties.Cors("*"),
                new SocleProperties.Instance("test", null, null),
                new SocleProperties.Folders(5)
        );
    }

    private OpenFgaBootstrap bootstrap(SocleProperties props) {
        var model = new ByteArrayResource(
                "{\"schema_version\":\"1.1\",\"type_definitions\":[]}".getBytes(StandardCharsets.UTF_8),
                "model.json"
        );
        return new OpenFgaBootstrap(client, props, model, stateDir.toString());
    }

    @Test
    void duplicateNamedStores_refuseToStart() throws Exception {
        Store a = store("id-a", "socle", OffsetDateTime.parse("2024-01-01T00:00:00Z"));
        Store b = store("id-b", "socle", OffsetDateTime.parse("2024-06-01T00:00:00Z"));
        stubListStores(List.of(a, b));

        OpenFgaBootstrap boot = bootstrap(properties);
        assertThatThrownBy(boot::resolveStoreId)
                .isInstanceOf(OpenFgaBootstrap.DuplicateOpenFgaStoreException.class)
                .hasMessageContaining("id-a")
                .hasMessageContaining("id-b")
                .hasMessageContaining("backup-restore.md");
        verify(client, never()).createStore(any());
    }

    @Test
    void configuredStoreMissing_refuseToStart() throws Exception {
        properties = withStoreId("missing-store-id");
        stubListStores(List.of(store("other", "other", OffsetDateTime.now())));

        OpenFgaBootstrap boot = bootstrap(properties);
        assertThatThrownBy(boot::resolveStoreId)
                .isInstanceOf(OpenFgaBootstrap.MissingConfiguredOpenFgaStoreException.class)
                .hasMessageContaining("missing-store-id")
                .hasMessageContaining("introuvable");
        verify(client, never()).createStore(any());
    }

    @Test
    void configuredStorePresent_reusesWithoutCreate() throws Exception {
        properties = withStoreId("known-id");
        stubListStores(List.of(store("known-id", "socle", OffsetDateTime.now())));

        assertThat(bootstrap(properties).resolveStoreId()).isEqualTo("known-id");
        verify(client, never()).createStore(any());
    }

    @Test
    void lostLocalCache_reusesSameNamedStoreWithoutCreate() throws Exception {
        // Simulate wiped state dir: no store.id file; OpenFGA still has one store named socle.
        Store existing = store("stable-store", "socle", OffsetDateTime.parse("2025-01-15T12:00:00Z"));
        stubListStores(List.of(existing));

        String id = bootstrap(properties).resolveStoreId();
        assertThat(id).isEqualTo("stable-store");
        verify(client, never()).createStore(any());
    }

    @Test
    void noStore_createsOnce() throws Exception {
        stubListStores(List.of());
        ClientCreateStoreResponse created = mock(ClientCreateStoreResponse.class);
        when(created.getId()).thenReturn("new-store");
        when(client.createStore(any(CreateStoreRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(created));

        assertThat(bootstrap(properties).resolveStoreId()).isEqualTo("new-store");
        verify(client).createStore(any(CreateStoreRequest.class));
    }

    private SocleProperties withStoreId(String storeId) {
        return new SocleProperties(
                properties.temporal(),
                new SocleProperties.OpenFga(
                        properties.openfga().apiUrl(),
                        storeId,
                        properties.openfga().authorizationModelId(),
                        properties.openfga().listObjectsMaxResults(),
                        properties.openfga().visibilityMigrationOnStartup(),
                        properties.openfga().maxChecksPerBatchCheck(),
                        properties.openfga().batchCheckParallelism(),
                        properties.openfga().documentCheckWarnThreshold()
                ),
                properties.cors(),
                properties.instance(),
                properties.folders()
        );
    }

    private void stubListStores(List<Store> stores) throws Exception {
        ClientListStoresResponse listed = mock(ClientListStoresResponse.class);
        when(listed.getStores()).thenReturn(stores);
        when(client.listStores()).thenReturn(CompletableFuture.completedFuture(listed));
    }

    private static Store store(String id, String name, OffsetDateTime created) {
        Store s = new Store();
        s.setId(id);
        s.setName(name);
        s.setCreatedAt(created);
        s.setUpdatedAt(created);
        return s;
    }
}
