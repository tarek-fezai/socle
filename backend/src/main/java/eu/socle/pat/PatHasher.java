// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/** {@code token_hash = HMAC-SHA256(secret, pepper)} ; comparaison à temps constant. */
public final class PatHasher {

    public static final int MIN_PEPPER_BYTES = 32;
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;
    private final byte[] decoy;

    public PatHasher(byte[] pepper) {
        if (pepper == null || pepper.length < MIN_PEPPER_BYTES) {
            throw new IllegalArgumentException("pepper PAT trop court (< " + MIN_PEPPER_BYTES + " octets)");
        }
        this.key = new SecretKeySpec(pepper.clone(), ALGORITHM);
        this.decoy = hash("socle-pat-decoy");
    }

    public byte[] hash(String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }

    /** {@code expected == null} (lookup inconnu) : même calcul et même comparaison, toujours faux. */
    public boolean matches(String secret, byte[] expected) {
        byte[] actual = hash(secret);
        boolean equal = MessageDigest.isEqual(actual, expected != null ? expected : decoy);
        return equal && expected != null;
    }

    @Override
    public String toString() {
        return "PatHasher[HmacSHA256]";
    }
}
