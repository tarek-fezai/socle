// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.attachment;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Ré-encodage des images raster pour retirer EXIF (GPS, etc.) — RGPD.
 * Renvoie aussi largeur/hauteur.
 */
@Component
public class ImageSanitizer {

    private static final Set<String> RASTER = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif");

    public record Result(byte[] bytes, String mediaType, Integer width, Integer height) {}

    public Optional<Result> sanitizeIfImage(byte[] original, String mediaType) throws IOException {
        if (mediaType == null) {
            return Optional.empty();
        }
        String mt = mediaType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (!RASTER.contains(mt) && !mt.equals("image/jpg")) {
            return Optional.empty();
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));
        if (image == null) {
            throw new IOException("image illisible");
        }
        String format = switch (mt) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "png"; // ImageIO webp write may be absent — PNG sans EXIF
            default -> "jpg";
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) {
            out.reset();
            if (!ImageIO.write(image, "jpg", out)) {
                throw new IOException("impossible de ré-encoder l'image");
            }
            format = "jpg";
        }
        String outType = switch (format) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            default -> "image/jpeg";
        };
        return Optional.of(new Result(out.toByteArray(), outType, image.getWidth(), image.getHeight()));
    }
}
