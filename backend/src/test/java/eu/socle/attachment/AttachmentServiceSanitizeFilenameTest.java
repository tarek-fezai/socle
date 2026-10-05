// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.attachment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentServiceSanitizeFilenameTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n", "\"'<>`", "dir/", "..\\"})
    void blankOrFullyStripped_fallsBackToDefault(String raw) {
        assertThat(AttachmentService.sanitizeFilename(raw)).isEqualTo("fichier");
    }

    @Test
    void stripsUnixAndWindowsPaths() {
        assertThat(AttachmentService.sanitizeFilename("../../etc/passwd")).isEqualTo("passwd");
        assertThat(AttachmentService.sanitizeFilename("C:\\Users\\bob\\rapport.pdf")).isEqualTo("rapport.pdf");
        assertThat(AttachmentService.sanitizeFilename("a/b\\c/d.txt")).isEqualTo("d.txt");
    }

    @Test
    void removesControlCharsQuotesAndMarkup() {
        assertThat(AttachmentService.sanitizeFilename("a\u0000b\r\nc\u007f.txt")).isEqualTo("abc.txt");
        assertThat(AttachmentService.sanitizeFilename("x\"y'z`.png")).isEqualTo("xyz.png");
        assertThat(AttachmentService.sanitizeFilename("<img onerror=x>.png")).isEqualTo("img onerror=x.png");
    }

    @Test
    void keepsUnicodeAndSpaces_trimsEdges() {
        assertThat(AttachmentService.sanitizeFilename("  Rapport été 2026.pdf  ")).isEqualTo("Rapport été 2026.pdf");
    }

    @Test
    void truncatesTo200Chars() {
        String name = "a".repeat(500) + ".pdf";
        assertThat(AttachmentService.sanitizeFilename(name)).hasSize(200);
    }

    @Test
    void contentDisposition_inlineForRasterImages_attachmentOtherwise() {
        assertThat(AttachmentService.contentDisposition(row("photo.png", "image/png")))
                .startsWith("inline; filename=\"photo.png\"");
        assertThat(AttachmentService.contentDisposition(row("doc.pdf", "application/pdf")))
                .startsWith("attachment; filename=\"doc.pdf\"");
        // jamais inline pour du HTML / SVG, même si l'extension ment.
        assertThat(AttachmentService.contentDisposition(row("x.png", "text/html"))).startsWith("attachment;");
        assertThat(AttachmentService.contentDisposition(row("x.png", "image/svg+xml"))).startsWith("attachment;");
    }

    @Test
    void contentDisposition_encodesNonAsciiFilename() {
        assertThat(AttachmentService.contentDisposition(row("été.pdf", "application/pdf")))
                .contains("filename*=UTF-8''%C3%A9t%C3%A9.pdf");
    }

    private static AttachmentService.AttachmentRow row(String filename, String mediaType) {
        return new AttachmentService.AttachmentRow(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), filename, mediaType,
                1L, "0".repeat(64), UUID.randomUUID(), null, null, Instant.now(), null, null);
    }
}
