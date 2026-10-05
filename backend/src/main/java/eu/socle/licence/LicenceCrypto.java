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

    private LicenceCrypto() {}

    public static PublicKey publicKeyFromRawBase64(String rawB64) {
        try {
            byte[] raw = Base64.getDecoder().decode(rawB64.trim());
            if (raw.length != 32) {
                throw new IllegalArgumentException("clé publique Ed25519 : 32 octets attendus");
            }
            // X.509 SubjectPublicKeyInfo pour Ed25519
            byte[] prefix = hex("302a300506032b6570032100");
            byte[] spki = new byte[prefix.length + raw.length];
            System.arraycopy(prefix, 0, spki, 0, prefix.length);
            System.arraycopy(raw, 0, spki, prefix.length, raw.length);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Clé publique licence invalide", e);
        }
    }

    public static byte[] canonicalPayload(JsonNode root) {
        ObjectNode ordered = MAPPER.createObjectNode();
        ordered.put("edition", requireText(root, "edition"));
        ordered.put("expiresAt", requireText(root, "expiresAt"));
        ordered.put("issuedAt", requireText(root, "issuedAt"));
        ordered.put("licenseId", requireText(root, "licenseId"));
        ordered.put("licensee", requireText(root, "licensee"));
        ordered.put("maxUsers", requireInt(root, "maxUsers"));
        // TreeMap pour stabiliser si évolution ; ici l'ordre ObjectNode Jackson suit l'insertion.
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

    /** Exposition test : même sérialisation que le signeur Node ({@code JSON.stringify} ordonné). */
    public static String canonicalJsonUtf8(JsonNode root) {
        return new String(canonicalPayload(root), StandardCharsets.UTF_8);
    }
}
