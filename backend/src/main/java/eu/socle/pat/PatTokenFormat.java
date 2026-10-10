// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Format {@code pat_<lookup>_<secret>} : lookup 12 caractères base62 (recherche indexée, non secret),
 * secret 32 octets {@link SecureRandom} encodés en base62 sur 43 caractères.
 */
public final class PatTokenFormat {

    public static final String PREFIX = "pat_";
    public static final int LOOKUP_LENGTH = 12;
    public static final int SECRET_BYTES = 32;
    /** ⌈256 / log2(62)⌉ : 43 caractères base62 couvrent 2^256. */
    public static final int SECRET_LENGTH = 43;

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final BigInteger BASE = BigInteger.valueOf(ALPHABET.length);
    private static final Pattern TOKEN = Pattern.compile(
            "^pat_([0-9A-Za-z]{" + LOOKUP_LENGTH + "})_([0-9A-Za-z]{" + SECRET_LENGTH + "})$");

    private PatTokenFormat() {}

    /** Jeton complet (affiché une seule fois) et ses parties. */
    public record Generated(String token, String lookup, String secret, String last4) {
        @Override
        public String toString() {
            return "PatTokenFormat.Generated[lookup=" + lookup + ", last4=" + last4 + "]";
        }
    }

    public record Parsed(String lookup, String secret) {
        @Override
        public String toString() {
            return "PatTokenFormat.Parsed[lookup=" + lookup + "]";
        }
    }

    public static Generated generate(SecureRandom random) {
        StringBuilder lookup = new StringBuilder(LOOKUP_LENGTH);
        for (int i = 0; i < LOOKUP_LENGTH; i++) {
            lookup.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        byte[] raw = new byte[SECRET_BYTES];
        random.nextBytes(raw);
        String secret = base62(raw, SECRET_LENGTH);
        String token = PREFIX + lookup + "_" + secret;
        return new Generated(token, lookup.toString(), secret, token.substring(token.length() - 4));
    }

    public static Optional<Parsed> parse(String token) {
        if (token == null || token.length() != PREFIX.length() + LOOKUP_LENGTH + 1 + SECRET_LENGTH) {
            return Optional.empty();
        }
        Matcher m = TOKEN.matcher(token);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(m.group(1), m.group(2)));
    }

    static String base62(byte[] bytes, int width) {
        BigInteger value = new BigInteger(1, bytes);
        char[] out = new char[width];
        for (int i = width - 1; i >= 0; i--) {
            BigInteger[] qr = value.divideAndRemainder(BASE);
            out[i] = ALPHABET[qr[1].intValue()];
            value = qr[0];
        }
        if (value.signum() != 0) {
            throw new IllegalArgumentException("largeur base62 insuffisante");
        }
        return new String(out);
    }
}
