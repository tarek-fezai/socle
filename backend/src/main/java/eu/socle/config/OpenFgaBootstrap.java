// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientCreateStoreResponse;
import dev.openfga.sdk.api.client.model.ClientListStoresResponse;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.Store;
import dev.openfga.sdk.api.model.TypeDefinition;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.security.MessageDigest;

/**
 * Crée le store OpenFGA et charge / met à jour le modèle s'ils sont absents
 * ou si le fingerprint du modèle classpath a changé. Idempotent — n'écrit pas dans {@code .env}.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "socle.openfga.auto-init", havingValue = "true", matchIfMissing = false)
public class OpenFgaBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OpenFgaBootstrap.class);
    private static final String STORE_NAME = "socle";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpenFgaClient client;
    private final SocleProperties properties;
    private final Resource modelResource;
    private final Path stateDir;

    public OpenFgaBootstrap(
            OpenFgaClient client,
            SocleProperties properties,
            @Value("classpath:openfga/model.json") Resource modelResource,
            @Value("${socle.openfga.state-dir:./data/openfga}") String stateDir
    ) {
        this.client = client;
        this.properties = properties;
        this.modelResource = modelResource;
        this.stateDir = Path.of(stateDir);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int attempts = 0;
        Exception last = null;
        while (attempts < 30) {
            attempts++;
            try {
                bootstrapOnce();
                return;
            } catch (Exception e) {
                last = e;
                log.warn("OpenFGA bootstrap tentative {}/30: {}", attempts, e.toString());
                Thread.sleep(2000L);
            }
        }
        throw new IllegalStateException("OpenFGA bootstrap échoué après 30 tentatives", last);
    }

    private void bootstrapOnce() throws Exception {
        String storeId = properties.openfga().storeId();
        if (storeId == null || storeId.isBlank()) {
            storeId = findOrCreateStore();
            log.info("OpenFGA store prêt (auto): {}", storeId);
        }
        client.setStoreId(storeId);

        String fingerprint = modelFingerprint();
        Path marker = stateDir.resolve("model.fingerprint");
        Path modelIdFile = stateDir.resolve("model.id");
        Path storeIdFile = stateDir.resolve("store.id");

        Files.createDirectories(stateDir);
        Files.writeString(storeIdFile, storeId, StandardCharsets.UTF_8);

        String configuredModelId = properties.openfga().authorizationModelId();
        String previousFp = Files.exists(marker) ? Files.readString(marker, StandardCharsets.UTF_8).trim() : "";

        if (configuredModelId != null && !configuredModelId.isBlank() && fingerprint.equals(previousFp)) {
            client.setAuthorizationModelId(configuredModelId);
            log.info("OpenFGA modèle configuré inchangé: {}", configuredModelId);
            return;
        }

        if (fingerprint.equals(previousFp) && Files.exists(modelIdFile)) {
            String existing = Files.readString(modelIdFile, StandardCharsets.UTF_8).trim();
            if (!existing.isBlank()) {
                client.setAuthorizationModelId(existing);
                log.info("OpenFGA modèle inchangé (fingerprint={}): {}", fingerprint, existing);
                return;
            }
        }

        String modelId = writeModel();
        client.setAuthorizationModelId(modelId);
        Files.writeString(marker, fingerprint, StandardCharsets.UTF_8);
        Files.writeString(modelIdFile, modelId, StandardCharsets.UTF_8);
        log.info("OpenFGA modèle publié: {} (fingerprint={})", modelId, fingerprint);
    }

    private String findOrCreateStore() throws Exception {
        ClientListStoresResponse listed = client.listStores().get();
        if (listed.getStores() != null) {
            for (Store store : listed.getStores()) {
                if (STORE_NAME.equals(store.getName())) {
                    return store.getId();
                }
            }
        }
        ClientCreateStoreResponse created = client.createStore(new CreateStoreRequest().name(STORE_NAME)).get();
        return created.getId();
    }

    private String writeModel() throws Exception {
        JsonNode root = MAPPER.readTree(modelResource.getInputStream());
        WriteAuthorizationModelRequest writeReq = new WriteAuthorizationModelRequest()
                .schemaVersion(root.get("schema_version").asText());
        List<TypeDefinition> defs = new ArrayList<>();
        for (JsonNode td : root.get("type_definitions")) {
            defs.add(MAPPER.treeToValue(td, TypeDefinition.class));
        }
        writeReq.setTypeDefinitions(defs);
        return client.writeAuthorizationModel(writeReq).get().getAuthorizationModelId();
    }

    private String modelFingerprint() throws Exception {
        byte[] bytes;
        try (var in = modelResource.getInputStream()) {
            bytes = in.readAllBytes();
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    }
}
