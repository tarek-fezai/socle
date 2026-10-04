// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.attachment;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageSanitizerTest {

    final ImageSanitizer sanitizer = new ImageSanitizer();

    @Test
    void jpegWithGpsExif_isReEncodedWithoutExif() throws Exception {
        byte[] original = TestImages.jpegWithGpsExif(8, 6);
        // Garde-fou du fixture : l'entrée contient bien EXIF + marqueur GPS.
        assertThat(TestImages.hasApp1Segment(original)).isTrue();
        assertThat(TestImages.contains(original, "Exif")).isTrue();
        assertThat(TestImages.contains(original, TestImages.GPS_MARKER)).isTrue();

        ImageSanitizer.Result result = sanitizer.sanitizeIfImage(original, "image/jpeg").orElseThrow();

        assertThat(result.mediaType()).isEqualTo("image/jpeg");
        assertThat(result.width()).isEqualTo(8);
        assertThat(result.height()).isEqualTo(6);
        assertThat(TestImages.hasApp1Segment(result.bytes())).isFalse();
        assertThat(TestImages.contains(result.bytes(), "Exif")).isFalse();
        assertThat(TestImages.contains(result.bytes(), TestImages.GPS_MARKER)).isFalse();
        // Toujours une image lisible.
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertThat(back).isNotNull();
        assertThat(back.getWidth()).isEqualTo(8);
    }

    @Test
    void png_isReEncoded_withDimensions() throws Exception {
        byte[] png = TestImages.png(5, 3);
        ImageSanitizer.Result result = sanitizer.sanitizeIfImage(png, "image/png").orElseThrow();
        assertThat(result.mediaType()).isEqualTo("image/png");
        assertThat(result.width()).isEqualTo(5);
        assertThat(result.height()).isEqualTo(3);
        assertThat(ImageIO.read(new ByteArrayInputStream(result.bytes()))).isNotNull();
    }

    @Test
    void mediaTypeWithParameters_isNormalised() throws Exception {
        assertThat(sanitizer.sanitizeIfImage(TestImages.png(2, 2), "IMAGE/PNG; charset=binary")).isPresent();
    }

    @Test
    void nonImage_isPassedThrough() throws Exception {
        byte[] pdf = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
        assertThat(sanitizer.sanitizeIfImage(pdf, "application/pdf")).isEmpty();
        assertThat(sanitizer.sanitizeIfImage(pdf, null)).isEmpty();
        assertThat(sanitizer.sanitizeIfImage(pdf, "image/svg+xml")).isEmpty();
    }

    @Test
    void undecodableImage_isRejectedWithIOException() {
        byte[] bogus = "not really a png".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> sanitizer.sanitizeIfImage(bogus, "image/png"))
                .isInstanceOf(java.io.IOException.class);
    }
}
