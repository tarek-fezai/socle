// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.attachment;

import eu.socle.web.ApiErrors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Traitement des images avant stockage :
 * <ul>
 *   <li>dimensions via {@link ImageReader} (métadonnées) <em>avant</em> tout décodage pixel — anti bombe ;</li>
 *   <li>PNG / JPEG / WebP : ré-encodage pour retirer EXIF (GPS) — RGPD ;</li>
 *   <li>GIF : stocké tel quel (pas d'EXIF GPS ; ré-encodage casserait l'animation).</li>
 * </ul>
 */
@Component
public class ImageSanitizer {

    private static final Set<String> RASTER = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif");

    private final long maxImagePixels;

    @Autowired
    public ImageSanitizer(AttachmentProperties properties) {
        this.maxImagePixels = Math.max(1, properties.maxImagePixels());
    }

    /** Visible pour tests (un seul constructeur public pour Spring). */
    static ImageSanitizer withMaxPixels(long maxImagePixels) {
        return new ImageSanitizer(maxImagePixels);
    }

    private ImageSanitizer(long maxImagePixels) {
        this.maxImagePixels = Math.max(1, maxImagePixels);
    }

    public record Result(byte[] bytes, String mediaType, Integer width, Integer height) {}

    public Optional<Result> sanitizeIfImage(byte[] original, String mediaType) throws IOException {
        if (mediaType == null) {
            return Optional.empty();
        }
        String mt = mediaType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (!RASTER.contains(mt)) {
            return Optional.empty();
        }

        ImageMeta meta = readMetaWithoutDecoding(original);
        long pixels = (long) meta.width() * (long) meta.height();
        if (pixels > maxImagePixels) {
            throw ApiErrors.imageTooLarge(meta.width(), meta.height(), maxImagePixels);
        }

        // GIF : conserver l'animation (et les trames) — pas d'EXIF GPS à retirer.
        if ("image/gif".equals(mt)) {
            return Optional.of(new Result(original, "image/gif", meta.width(), meta.height()));
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));
        if (image == null) {
            throw new IOException("image illisible");
        }
        String format = switch (mt) {
            case "image/png" -> "png";
            case "image/webp" -> "png"; // writer WebP optionnel — PNG sans métadonnées
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
            default -> "image/jpeg";
        };
        return Optional.of(new Result(out.toByteArray(), outType, image.getWidth(), image.getHeight()));
    }

    /**
     * Largeur/hauteur via métadonnées du conteneur — sans décoder les pixels
     * (évite l'allocation d'un buffer 50k×50k pour un PNG IHDR mensonger).
     */
    static ImageMeta readMetaWithoutDecoding(byte[] bytes) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                throw new IOException("image illisible");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IOException("image illisible");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new IOException("dimensions image invalides");
                }
                return new ImageMeta(width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Compte les trames d'un GIF (métadonnées ImageReader) — pour tests. */
    static int countFrames(byte[] bytes) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return 0;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, false, true);
                return reader.getNumImages(true);
            } finally {
                reader.dispose();
            }
        }
    }

    record ImageMeta(int width, int height) {}
}
