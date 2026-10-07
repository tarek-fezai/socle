// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.attachment;

import eu.socle.web.ApiErrors;
import eu.socle.web.CodedStatusException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageSanitizerTest {

    final ImageSanitizer sanitizer = ImageSanitizer.withMaxPixels(40_000_000L);

    @Test
    void jpegWithGpsExif_isReEncodedWithoutExif() throws Exception {
        byte[] original = TestImages.jpegWithGpsExif(8, 6);
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

    /**
     * Bombe de décompression : IHDR 50000×50000 dans un PNG minuscule.
     * Doit être refusé via métadonnées ImageReader <em>avant</em> tout décodage pixel
     * (doit aussi passer sous {@code -Xmx256m}).
     */
    @Test
    void decompressionBomb_pngDeclaringHugeDimensions_is413_imageTooLarge_withoutDecoding() throws Exception {
        byte[] bomb = TestImages.pngDeclaringDimensions(50_000, 50_000);
        assertThat(bomb.length).isLessThan(256);

        ImageSanitizer.ImageMeta meta = ImageSanitizer.readMetaWithoutDecoding(bomb);
        assertThat(meta.width()).isEqualTo(50_000);
        assertThat(meta.height()).isEqualTo(50_000);

        assertThatThrownBy(() -> sanitizer.sanitizeIfImage(bomb, "image/png"))
                .isInstanceOf(CodedStatusException.class)
                .satisfies(ex -> {
                    CodedStatusException cse = (CodedStatusException) ex;
                    assertThat(cse.getStatusCode().value()).isEqualTo(413);
                    assertThat(cse.getCode()).isEqualTo(ApiErrors.IMAGE_TOO_LARGE);
                });
    }

    @Test
    void animatedGif_isStoredAsIs_preservingTwoFrames() throws Exception {
        byte[] gif = TestImages.animatedGifTwoFrames();
        assertThat(ImageSanitizer.countFrames(gif)).isEqualTo(2);

        ImageSanitizer.Result result = sanitizer.sanitizeIfImage(gif, "image/gif").orElseThrow();
        assertThat(result.mediaType()).isEqualTo("image/gif");
        assertThat(result.bytes()).isEqualTo(gif);
        assertThat(ImageSanitizer.countFrames(result.bytes())).isEqualTo(2);
        assertThat(result.width()).isEqualTo(8);
        assertThat(result.height()).isEqualTo(8);
    }

    @Test
    void webp_isReadableAndSanitized() throws Exception {
        for (byte[] webp : new byte[][] {TestImages.webpLossy(), TestImages.webpLossless()}) {
            String detected = new MediaTypeDetector().detect(new ByteArrayInputStream(webp), "pic.webp");
            assertThat(detected).isEqualTo("image/webp");

            ImageSanitizer.Result result = sanitizer.sanitizeIfImage(webp, "image/webp").orElseThrow();
            // Ré-encodage PNG (writer WebP optionnel) — sans métadonnées.
            assertThat(result.mediaType()).isIn("image/png", "image/webp", "image/jpeg");
            assertThat(result.width()).isPositive();
            assertThat(result.height()).isPositive();
            assertThat(ImageIO.read(new ByteArrayInputStream(result.bytes()))).isNotNull();
            assertThat(TestImages.contains(result.bytes(), "Exif")).isFalse();
            assertThat(TestImages.contains(result.bytes(), TestImages.GPS_MARKER)).isFalse();
        }
    }
}
