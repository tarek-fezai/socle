// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.attachment;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/** Détection MIME par octets magiques (Tika) — jamais par extension ni Content-Type client. */
@Component
public class MediaTypeDetector {

    private final Tika tika = new Tika();

    public String detect(InputStream stream, String filenameHint) throws IOException {
        String detected = tika.detect(stream, filenameHint);
        if (detected == null || detected.isBlank()) {
            return "application/octet-stream";
        }
        return detected.split(";")[0].trim().toLowerCase();
    }
}
