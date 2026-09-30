package eu.socle.integrations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Hash / génération de secrets (webhook, préfixes SIEM) — jamais loguer le clair. */
final class SecretSupport {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretSupport() {}

    static String generateWebhookSecret() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return "whsec_" + HexFormat.of().formatHex(raw);
    }

    static String sha256Hex(String plaintext) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Préfixe affichable type api_keys.key_prefix — 8 premiers caractères + ellipsis. */
    static String prefixOf(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String s = secret.trim();
        if (s.length() <= 8) {
            return s.charAt(0) + "••••";
        }
        return s.substring(0, 8) + "…";
    }
}
