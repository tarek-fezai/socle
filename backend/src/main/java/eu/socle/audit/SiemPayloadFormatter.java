// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Formate le payload SIEM selon {@code siem_connectors.config.format}.
 * HEC Splunk = format supporté en premier ; Datadog / Sentinel = variantes JSON simples.
 */
@Component
public class SiemPayloadFormatter {

    public static final String FORMAT_HEC = "hec";
    public static final String FORMAT_DATADOG = "datadog";
    public static final String FORMAT_SENTINEL = "sentinel";
    public static final String FORMAT_JSON = "json";

    private final ObjectMapper objectMapper;

    public SiemPayloadFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String format(
            String provider,
            String format,
            long auditEventId,
            UUID actorId,
            boolean actorIsSystem,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, Object> metadata,
            Instant createdAt
    ) {
        String effectiveFormat = resolveFormat(provider, format);
        ObjectNode event = objectMapper.createObjectNode();
        event.put("audit_event_id", auditEventId);
        event.put("action", action);
        event.put("resource_type", resourceType);
        if (resourceId != null) {
            event.put("resource_id", resourceId.toString());
        } else {
            event.putNull("resource_id");
        }
        if (actorId != null) {
            event.put("actor_id", actorId.toString());
        } else {
            event.putNull("actor_id");
        }
        event.put("actor_is_system", actorIsSystem);
        event.put("created_at", createdAt != null ? createdAt.toString() : Instant.now().toString());
        event.set("metadata", objectMapper.valueToTree(metadata != null ? metadata : Map.of()));

        try {
            return switch (effectiveFormat) {
                case FORMAT_HEC -> hecEnvelope(event, action);
                case FORMAT_DATADOG -> datadogEnvelope(event, action);
                case FORMAT_SENTINEL -> sentinelEnvelope(event, action);
                default -> objectMapper.writeValueAsString(event);
            };
        } catch (JsonProcessingException e) {
            return "{\"error\":\"siem_payload_serialization_failed\",\"action\":\"" + action + "\"}";
        }
    }

    static String resolveFormat(String provider, String format) {
        if (format != null && !format.isBlank()) {
            return format.trim().toLowerCase();
        }
        if (provider == null) {
            return FORMAT_JSON;
        }
        return switch (provider.toLowerCase()) {
            case "splunk" -> FORMAT_HEC;
            case "datadog" -> FORMAT_DATADOG;
            case "sentinel" -> FORMAT_SENTINEL;
            default -> FORMAT_JSON;
        };
    }

    private String hecEnvelope(ObjectNode event, String action) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("event", event);
        root.put("sourcetype", "socle:audit");
        root.put("source", "socle");
        ObjectNode fields = objectMapper.createObjectNode();
        fields.put("event_type", action);
        root.set("fields", fields);
        return objectMapper.writeValueAsString(root);
    }

    private String datadogEnvelope(ObjectNode event, String action) throws JsonProcessingException {
        ArrayNode arr = objectMapper.createArrayNode();
        ObjectNode row = objectMapper.createObjectNode();
        row.put("message", action);
        row.put("ddsource", "socle");
        row.put("service", "socle-backend");
        row.put("status", "info");
        row.set("attributes", event);
        arr.add(row);
        return objectMapper.writeValueAsString(arr);
    }

    private String sentinelEnvelope(ObjectNode event, String action) throws JsonProcessingException {
        ArrayNode arr = objectMapper.createArrayNode();
        ObjectNode row = objectMapper.createObjectNode();
        row.put("EventType", action);
        row.put("TimeGenerated", Instant.now().toString());
        row.set("Payload", event);
        arr.add(row);
        return objectMapper.writeValueAsString(arr);
    }

    public record ConnectorConfig(String endpoint, String format, String hecToken, String apiKey, String sharedKey) {
        public static ConnectorConfig fromJson(ObjectMapper mapper, String configJson) {
            try {
                JsonNode n = mapper.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
                return new ConnectorConfig(
                        text(n, "endpoint"),
                        text(n, "format"),
                        firstText(n, "hec_token", "token"),
                        firstText(n, "api_key", "dd_api_key"),
                        firstText(n, "shared_key", "authorization")
                );
            } catch (JsonProcessingException e) {
                return new ConnectorConfig(null, null, null, null, null);
            }
        }

        private static String text(JsonNode n, String field) {
            JsonNode v = n.get(field);
            return v == null || v.isNull() ? null : v.asText();
        }

        private static String firstText(JsonNode n, String... fields) {
            for (String f : fields) {
                String v = text(n, f);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
            return null;
        }
    }

    /** Corps d’événement neutre (tests / introspection) sans secrets. */
    public Map<String, Object> baseEventMap(
            long auditEventId,
            String action,
            String resourceType,
            UUID resourceId
    ) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("audit_event_id", auditEventId);
        m.put("action", action);
        m.put("resource_type", resourceType);
        m.put("resource_id", resourceId != null ? resourceId.toString() : null);
        return m;
    }
}
