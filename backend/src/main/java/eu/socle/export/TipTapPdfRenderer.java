// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.export;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import eu.socle.document.TransclusionResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rendu TipTap → PDF. Les blocs transclusion inaccessibles affichent
 * {@link #INACCESSIBLE_LABEL} — jamais omis, jamais le contenu cible.
 */
public final class TipTapPdfRenderer {

    /** Même libellé que la vue web ({@code CompositePage}). */
    public static final String INACCESSIBLE_LABEL = "Contenu non accessible";

    private TipTapPdfRenderer() {}

    public static byte[] renderDocument(String title, Map<String, Object> resolvedBody) {
        List<String> lines = flatten(resolvedBody);
        return toPdf(title != null ? title : "Document", lines);
    }

    /** PDF multi-documents (dossier / tag) — titre de collection puis chaque doc. */
    public static byte[] renderCollection(String collectionTitle, List<NamedBody> documents) {
        List<String> lines = new ArrayList<>();
        for (NamedBody doc : documents) {
            lines.add("--- " + doc.title() + " ---");
            lines.addAll(flatten(doc.body()));
            lines.add("");
        }
        return toPdf(collectionTitle != null ? collectionTitle : "Export", lines);
    }

    public record NamedBody(String title, Map<String, Object> body) {}

    /** Texte plat dérivé (tests / debug) — même parcours que le PDF. */
    public static String toPlainText(String title, Map<String, Object> resolvedBody) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(title).append('\n');
        }
        for (String line : flatten(resolvedBody)) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** Texte extrait du PDF (tests / vérif anti-fuite). */
    public static String extractText(byte[] pdfBytes) {
        try {
            PdfReader reader = new PdfReader(pdfBytes);
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            int pages = reader.getNumberOfPages();
            for (int i = 1; i <= pages; i++) {
                sb.append(extractor.getTextFromPage(i)).append('\n');
            }
            reader.close();
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Lecture PDF impossible", e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<String> flatten(Map<String, Object> node) {
        List<String> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        String type = String.valueOf(node.getOrDefault("type", ""));
        if (TransclusionResolver.NODE_TYPE.equals(type)) {
            Map<String, Object> attrs = attrsOf(node);
            Object accessible = attrs.get(TransclusionResolver.ATTR_ACCESSIBLE);
            if (Boolean.FALSE.equals(accessible) || "false".equals(String.valueOf(accessible))) {
                out.add(INACCESSIBLE_LABEL);
                return out;
            }
            Object title = attrs.get(TransclusionResolver.ATTR_TITLE);
            if (title != null) {
                out.add(String.valueOf(title));
            }
            Object content = node.get("content");
            if (content instanceof List<?> children) {
                for (Object child : children) {
                    if (child instanceof Map<?, ?> m) {
                        out.addAll(flatten((Map<String, Object>) m));
                    }
                }
            }
            return out;
        }
        if ("text".equals(type)) {
            Object text = node.get("text");
            if (text != null) {
                out.add(String.valueOf(text));
            }
            return out;
        }
        if ("image".equals(type) || "attachment".equals(type)) {
            Map<String, Object> attrs = attrsOf(node);
            Object filename = attrs.get("filename");
            Object alt = attrs.get("alt");
            Object size = attrs.get("sizeBytes");
            String label = filename != null ? String.valueOf(filename)
                    : (alt != null && !String.valueOf(alt).isBlank() ? String.valueOf(alt) : "Pièce jointe");
            if ("image".equals(type)) {
                out.add("[Image] " + label);
            } else if (size instanceof Number n) {
                out.add("[Fichier joint] " + label + " (" + n.longValue() + " o)");
            } else {
                out.add("[Fichier joint] " + label);
            }
            return out;
        }
        Object content = node.get("content");
        if (content instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> m) {
                    out.addAll(flatten((Map<String, Object>) m));
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attrsOf(Map<String, Object> node) {
        Object attrs = node.get("attrs");
        if (attrs instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static byte[] toPdf(String title, List<String> lines) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document document = new Document();
            PdfWriter.getInstance(document, baos);
            document.open();
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 11);
            document.add(new Paragraph(title, titleFont));
            document.add(new Paragraph(" ", bodyFont));
            for (String line : lines) {
                document.add(new Paragraph(line == null || line.isEmpty() ? " " : line, bodyFont));
            }
            document.close();
            return baos.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Échec génération PDF", e);
        }
    }
}
