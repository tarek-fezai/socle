// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.integrations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditActorResolver;
import eu.socle.audit.AuditService;
import eu.socle.audit.SiemPayloadFormatter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD {@code siem_connectors}. Les secrets de {@code config} sont stockés en JSONB
 * et masqués (préfixe seulement) sur toute lecture. Le test HTTP ne mute pas {@code status}.
 */
@Service
public class SiemConnectorService {

    private static final Set<String> PROVIDERS = Set.of("splunk", "datadog", "sentinel");
    private static final Set<String> STATUSES = Set.of("connected", "disconnected", "error");
    private static final Set<String> SECRET_KEYS = Set.of(
            "hec_token", "token", "api_key", "dd_api_key", "shared_key", "authorization");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SiemPayloadFormatter formatter;
    private final SiemHttpClient httpClient;
    private final AuditService auditService;
    private final AuditActorResolver auditActorResolver;

    public SiemConnectorService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            SiemPayloadFormatter formatter,
            SiemHttpClient httpClient,
            AuditService auditService,
            AuditActorResolver auditActorResolver
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.formatter = formatter;
        this.httpClient = httpClient;
        this.auditService = auditService;
        this.auditActorResolver = auditActorResolver;
    }

    public List<ConnectorView> list() {
        return jdbc.query(
                "SELECT id, provider, config::text AS config, status, connected_at FROM siem_connectors ORDER BY provider",
                (rs, i) -> toView(
                        (UUID) rs.getObject("id"),
                        rs.getString("provider"),
                        rs.getString("config"),
                        rs.getString("status"),
                        rs.getTimestamp("connected_at")
                ));
    }

    public ConnectorView get(UUID id) {
        List<ConnectorView> rows = jdbc.query(
                "SELECT id, provider, config::text AS config, status, connected_at FROM siem_connectors WHERE id = ?",
                (rs, i) -> toView(
                        (UUID) rs.getObject("id"),
                        rs.getString("provider"),
                        rs.getString("config"),
                        rs.getString("status"),
                        rs.getTimestamp("connected_at")
                ),
                id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Connecteur introuvable");
        }
        return rows.getFirst();
    }

    public ConnectorView create(String provider, Map<String, Object> config) {
        String p = requireProvider(provider);
        String stored = storeConfigJson(null, config);
        requireEndpoint(stored);
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO siem_connectors (id, provider, config, status, connected_at)
                VALUES (?, ?, CAST(? AS jsonb), 'disconnected', NULL)
                """,
                id, p, stored);
        ConnectorView created = get(id);
        auditService.record(
                auditActorResolver.currentUserId().orElse(null),
                false,
                AuditActions.SIEM_CONNECTOR_CREATED,
                "siem_connector",
                id,
                Map.of("provider", p),
                null);
        return created;
    }

    public ConnectorView update(UUID id, Map<String, Object> config, String status) {
        ConnectorRow existing = loadRaw(id);
        String stored = existing.configJson();
        if (config != null) {
            stored = storeConfigJson(stored, config);
            requireEndpoint(stored);
        }
        String newStatus = existing.status();
        Timestamp connectedAt = existing.connectedAt() != null
                ? Timestamp.from(existing.connectedAt())
                : null;
        if (status != null && !status.isBlank()) {
            String s = status.trim().toLowerCase();
            if (!STATUSES.contains(s)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status invalide");
            }
            newStatus = s;
            if ("connected".equals(s) && existing.connectedAt() == null) {
                connectedAt = Timestamp.from(Instant.now());
            }
            if ("disconnected".equals(s)) {
                connectedAt = null;
            }
        }
        jdbc.update(
                """
                UPDATE siem_connectors
                   SET config = CAST(? AS jsonb), status = ?, connected_at = ?
                 WHERE id = ?
                """,
                stored, newStatus, connectedAt, id);
        ConnectorView updated = get(id);
        auditService.record(
                auditActorResolver.currentUserId().orElse(null),
                false,
                AuditActions.SIEM_CONNECTOR_UPDATED,
                "siem_connector",
                id,
                Map.of(
                        "provider", updated.provider(),
                        "status", updated.status()
                ),
                null);
        return updated;
    }

    public void delete(UUID id) {
        ConnectorRow existing = loadRaw(id);
        int n = jdbc.update("DELETE FROM siem_connectors WHERE id = ?", id);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Connecteur introuvable");
        }
        auditService.record(
                auditActorResolver.currentUserId().orElse(null),
                false,
                AuditActions.SIEM_CONNECTOR_DELETED,
                "siem_connector",
                id,
                Map.of("provider", existing.provider()),
                null);
    }

    /**
     * Envoie un événement de test formaté. Ne modifie jamais {@code status}
     * (succès ou échec) — l'activation explicite se fait via PUT status=connected.
     */
    public TestResult test(UUID id) {
        ConnectorRow row = loadRaw(id);
        var cfg = SiemPayloadFormatter.ConnectorConfig.fromJson(objectMapper, row.configJson());
        if (cfg.endpoint() == null || cfg.endpoint().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endpoint manquant dans la config");
        }
        String payload = formatter.format(
                row.provider(),
                cfg.format(),
                0L,
                null,
                true,
                "siem.connection_test",
                "system",
                null,
                Map.of("probe", true),
                Instant.now()
        );
        Map<String, String> headers = authHeaders(row.provider(), cfg);
        SiemHttpClient.Result http = httpClient.post(cfg.endpoint(), payload, headers);
        return new TestResult(http.ok(), http.statusCode(), http.ok() ? "Connexion OK" : http.errorMessage());
    }

    private Map<String, String> authHeaders(String provider, SiemPayloadFormatter.ConnectorConfig cfg) {
        Map<String, String> h = new LinkedHashMap<>();
        return switch (provider.toLowerCase()) {
            case "splunk" -> {
                String token = firstNonBlank(cfg.hecToken());
                if (token == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hec_token manquant");
                }
                h.put("Authorization", "Splunk " + token);
                yield h;
            }
            case "datadog" -> {
                String key = firstNonBlank(cfg.apiKey());
                if (key == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "api_key manquant");
                }
                h.put("DD-API-KEY", key);
                yield h;
            }
            case "sentinel" -> {
                String key = firstNonBlank(cfg.sharedKey());
                if (key == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "shared_key manquant");
                }
                h.put("Authorization", key);
                h.put("Log-Type", "SocleAudit");
                yield h;
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider non supporté");
        };
    }

    private ConnectorRow loadRaw(UUID id) {
        List<ConnectorRow> rows = jdbc.query(
                "SELECT id, provider, config::text AS config, status, connected_at FROM siem_connectors WHERE id = ?",
                (rs, i) -> {
                    Timestamp ca = rs.getTimestamp("connected_at");
                    return new ConnectorRow(
                            (UUID) rs.getObject("id"),
                            rs.getString("provider"),
                            rs.getString("config"),
                            rs.getString("status"),
                            ca != null ? ca.toInstant() : null
                    );
                },
                id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Connecteur introuvable");
        }
        return rows.getFirst();
    }

    private ConnectorView toView(UUID id, String provider, String configJson, String status, Timestamp connectedAt) {
        return new ConnectorView(
                id,
                provider,
                redactConfig(configJson),
                status,
                connectedAt != null ? connectedAt.toInstant() : null
        );
    }

    /** Fusionne la config entrante avec l'existante ; conserve les secrets si champ vide/absent. */
    String storeConfigJson(String existingJson, Map<String, Object> incoming) {
        ObjectNode out = objectMapper.createObjectNode();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                JsonNode prev = objectMapper.readTree(existingJson);
                if (prev.isObject()) {
                    out = (ObjectNode) prev.deepCopy();
                }
            } catch (JsonProcessingException ignored) {
                out = objectMapper.createObjectNode();
            }
        }
        if (incoming != null) {
            for (Map.Entry<String, Object> e : incoming.entrySet()) {
                String key = e.getKey();
                Object val = e.getValue();
                if (SECRET_KEYS.contains(key)) {
                    if (val == null) {
                        continue;
                    }
                    String s = String.valueOf(val).trim();
                    if (s.isEmpty() || s.contains("…") || s.contains("••••")) {
                        continue; // garder l'existant
                    }
                    out.put(key, s);
                } else if (val == null) {
                    out.putNull(key);
                } else {
                    out.putPOJO(key, val);
                }
            }
        }
        try {
            return objectMapper.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "config JSON invalide");
        }
    }

    Map<String, Object> redactConfig(String configJson) {
        Map<String, Object> safe = new LinkedHashMap<>();
        try {
            JsonNode n = objectMapper.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
            if (!n.isObject()) {
                return safe;
            }
            n.fields().forEachRemaining(e -> {
                String key = e.getKey();
                JsonNode v = e.getValue();
                if (SECRET_KEYS.contains(key)) {
                    String secret = v.isNull() ? null : v.asText();
                    safe.put(key + "_prefix", SecretSupport.prefixOf(secret));
                    // jamais la valeur claire
                } else if (v.isNull()) {
                    safe.put(key, null);
                } else if (v.isNumber()) {
                    safe.put(key, v.numberValue());
                } else if (v.isBoolean()) {
                    safe.put(key, v.booleanValue());
                } else if (v.isTextual()) {
                    safe.put(key, v.asText());
                } else {
                    safe.put(key, v.toString());
                }
            });
        } catch (JsonProcessingException e) {
            return safe;
        }
        return safe;
    }

    private static void requireEndpoint(String configJson) {
        try {
            JsonNode n = new ObjectMapper().readTree(configJson);
            JsonNode ep = n.get("endpoint");
            if (ep == null || ep.asText().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "config.endpoint requis");
            }
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "config JSON invalide");
        }
    }

    private static String requireProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider requis");
        }
        String p = provider.trim().toLowerCase();
        if (!PROVIDERS.contains(p)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider invalide");
        }
        return p;
    }

    private static String firstNonBlank(String v) {
        return v != null && !v.isBlank() ? v : null;
    }

    public record ConnectorView(
            UUID id,
            String provider,
            Map<String, Object> config,
            String status,
            Instant connectedAt
    ) {}

    public record TestResult(boolean ok, int httpStatus, String message) {}

    record ConnectorRow(UUID id, String provider, String configJson, String status, Instant connectedAt) {}
}
