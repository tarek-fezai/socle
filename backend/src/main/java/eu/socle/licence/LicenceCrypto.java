// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.licence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Vérification Ed25519 hors ligne d'un fichier de licence JSON.
 * Payload signé = JSON compact des champs métier (clés triées), sans {@code signature}.
 */
public final class LicenceCrypto {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] ED25519_SPKI_PREFIX = hex("302a300506032b6570032100");

    private LicenceCrypto() {}

    /**
     * Accepte : raw 32 octets Base64 ; DER SPKI Base64 (44 octets) ; PEM
     * {@code BEGIN PUBLIC KEY} (openssl pkey -pubout).
     */
    public static PublicKey publicKeyFromEncoded(String encoded) {
        try {
            String trimmed = encoded == null ? "" : encoded.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("clé publique vide");
            }
            byte[] der;
            if (trimmed.contains("BEGIN PUBLIC KEY")) {
                String b64 = trimmed
                        .replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .replaceAll("\\s", "");
                der = Base64.getDecoder().decode(b64);
            } else {
                byte[] raw = Base64.getDecoder().decode(trimmed.replaceAll("\\s", ""));
                if (raw.length == 32) {
                    der = new byte[ED25519_SPKI_PREFIX.length + 32];
                    System.arraycopy(ED25519_SPKI_PREFIX, 0, der, 0, ED25519_SPKI_PREFIX.length);
                    System.arraycopy(raw, 0, der, ED25519_SPKI_PREFIX.length, 32);
                } else if (raw.length == 44) {
                    der = raw;
                } else {
                    throw new IllegalArgumentException(
                            "clé publique Ed25519 : raw 32 octets, SPKI 44 octets ou PEM attendus (obtenu "
                                    + raw.length + ")");
                }
            }
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Clé publique licence invalide", e);
        }
    }

    /** @deprecated préférer {@link #publicKeyFromEncoded(String)} */
    @Deprecated
    public static PublicKey publicKeyFromRawBase64(String rawB64) {
        return publicKeyFromEncoded(rawB64);
    }

    public static byte[] canonicalPayload(JsonNode root) {
        ObjectNode ordered = MAPPER.createObjectNode();
        ordered.put("edition", requireText(root, "edition"));
        ordered.put("expiresAt", requireText(root, "expiresAt"));
        ordered.put("issuedAt", requireText(root, "issuedAt"));
        ordered.put("licenseId", requireText(root, "licenseId"));
        ordered.put("licensee", requireText(root, "licensee"));
        ordered.put("maxUsers", requireInt(root, "maxUsers"));
        try {
            Map<String, Object> map = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = ordered.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                map.put(e.getKey(), MAPPER.treeToValue(e.getValue(), Object.class));
            }
            return MAPPER.writeValueAsBytes(map);
        } catch (Exception e) {
            throw new IllegalArgumentException("Payload licence non sérialisable", e);
        }
    }

    public static boolean verify(JsonNode root, PublicKey publicKey) {
        JsonNode sigNode = root.get("signature");
        if (sigNode == null || !sigNode.isTextual() || sigNode.asText().isBlank()) {
            return false;
        }
        try {
            byte[] payload = canonicalPayload(root);
            byte[] sig = Base64.getDecoder().decode(sigNode.asText().trim());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payload);
            return verifier.verify(sig);
        } catch (Exception e) {
            return false;
        }
    }

    private static String requireText(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            throw new IllegalArgumentException("champ requis : " + field);
        }
        return n.asText();
    }

    private static int requireInt(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.canConvertToInt() || n.asInt() < 1) {
            throw new IllegalArgumentException("champ requis (entier ≥ 1) : " + field);
        }
        return n.asInt();
    }

    private static byte[] hex(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String canonicalJsonUtf8(JsonNode root) {
        return new String(canonicalPayload(root), StandardCharsets.UTF_8);
    }
}
