// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.attachment;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Images minuscules générées en mémoire pour les tests pièces jointes. */
final class TestImages {

    /** Marqueur ASCII planté dans le bloc GPS EXIF — ne doit jamais survivre au ré-encodage. */
    static final String GPS_MARKER = "SECRET-GPS-MARKER";

    private TestImages() {}

    static BufferedImage rgb(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, ((x * 40) << 16) | ((y * 40) << 8) | 0x80);
            }
        }
        return img;
    }

    static byte[] png(int w, int h) {
        return encode(rgb(w, h), "png");
    }

    static byte[] jpeg(int w, int h) {
        return encode(rgb(w, h), "jpg");
    }

    /**
     * PNG minuscule dont l'IHDR déclare {@code width}×{@code height} (bombe de décompression).
     * Les métadonnées ImageReader exposent ces dimensions sans décoder les pixels.
     */
    static byte[] pngDeclaringDimensions(int width, int height) {
        byte[] signature = new byte[] {
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
        };
        ByteBuffer ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN);
        ihdr.putInt(width).putInt(height);
        ihdr.put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        byte[] ihdrChunk = pngChunk("IHDR", ihdr.array());
        // Une ligne filtrée 0 + RGB noir pour 1×1 — IDAT valide mais dimensions IHDR mensongères.
        byte[] rawScan = new byte[] {0, 0, 0, 0};
        byte[] compressed = deflate(rawScan);
        byte[] idatChunk = pngChunk("IDAT", compressed);
        byte[] iendChunk = pngChunk("IEND", new byte[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                signature.length + ihdrChunk.length + idatChunk.length + iendChunk.length);
        try {
            out.write(signature);
            out.write(ihdrChunk);
            out.write(idatChunk);
            out.write(iendChunk);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** GIF animé à exactement 2 trames (8×8). */
    static byte[] animatedGifTwoFrames() {
        try {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
            if (!writers.hasNext()) {
                throw new IllegalStateException("pas de writer GIF");
            }
            ImageWriter writer = writers.next();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                IIOMetadata streamMeta = writer.getDefaultStreamMetadata(param);
                writer.prepareWriteSequence(streamMeta);
                for (int i = 0; i < 2; i++) {
                    BufferedImage frame = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = frame.createGraphics();
                    g.setColor(i == 0 ? Color.RED : Color.BLUE);
                    g.fillRect(0, 0, 8, 8);
                    g.dispose();
                    IIOMetadata imageMeta = writer.getDefaultImageMetadata(
                            ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB),
                            param);
                    writer.writeToSequence(new IIOImage(frame, null, imageMeta), param);
                }
                writer.endWriteSequence();
            } finally {
                writer.dispose();
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** WebP réel (lossy) via ImageIO + TwelveMonkeys si un writer est présent, sinon fixture RIFF minimale. */
    static byte[] webp(int w, int h) {
        try {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
            if (!writers.hasNext()) {
                writers = ImageIO.getImageWritersByFormatName("webp");
            }
            if (writers.hasNext()) {
                ImageWriter writer = writers.next();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                    writer.setOutput(ios);
                    writer.write(null, new IIOImage(rgb(w, h), null, null), null);
                } finally {
                    writer.dispose();
                }
                byte[] bytes = baos.toByteArray();
                if (bytes.length > 0) {
                    return bytes;
                }
            }
        } catch (IOException ignored) {
            // fallback ci-dessous
        }
        // VP8L lossless 1×1 opaque noir — RIFF/WEBP valide (lu par TwelveMonkeys / libwebp).
        return hexToBytes(
                "5249464620000000574542505650384C130000002F000000100710117118080200"
        );
    }

    private static byte[] pngChunk(String type, byte[] data) {
        ByteBuffer buf = ByteBuffer.allocate(8 + data.length + 4).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        buf.put(typeBytes);
        buf.put(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        buf.putInt((int) crc.getValue());
        return buf.array();
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        byte[] buf = new byte[64];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] hexToBytes(String hex) {
        int n = hex.length();
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }

    /** JPEG valide avec un segment APP1/Exif contenant un IFD GPS (latitude + {@link #GPS_MARKER}). */
    static byte[] jpegWithGpsExif(int w, int h) {
        byte[] plain = jpeg(w, h);
        byte[] exif = gpsExifSegment();
        ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length + exif.length);
        out.write(plain, 0, 2); // SOI
        out.writeBytes(exif);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    static boolean contains(byte[] haystack, String ascii) {
        return contains(haystack, ascii.getBytes(StandardCharsets.US_ASCII));
    }

    /** {@code true} si le flux JPEG contient un segment APP1 (0xFFE1) — EXIF/XMP. */
    static boolean hasApp1Segment(byte[] jpeg) {
        int i = 2;
        while (i + 4 <= jpeg.length) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                return false;
            }
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xE1) {
                return true;
            }
            if (marker == 0xDA) { // SOS : début des données image
                return false;
            }
            int len = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            i += 2 + len;
        }
        return false;
    }

    private static byte[] encode(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(img, format, out)) {
                throw new IllegalStateException("writer absent: " + format);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** APP1 : "Exif\0\0" + TIFF big-endian (IFD0 → pointeur GPS → IFD GPS). */
    private static byte[] gpsExifSegment() {
        byte[] processing = ("ASCII\0\0\0" + GPS_MARKER).getBytes(StandardCharsets.US_ASCII);

        int gpsIfdOffset = 8 + 2 + 12 + 4;          // après header + IFD0 (1 entrée)
        int gpsIfdSize = 2 + 3 * 12 + 4;            // 3 entrées
        int latDataOffset = gpsIfdOffset + gpsIfdSize;
        int procDataOffset = latDataOffset + 24;     // 3 rationnels
        int tiffLen = procDataOffset + processing.length;

        ByteBuffer tiff = ByteBuffer.allocate(tiffLen); // big-endian par défaut
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        // IFD0 : GPSInfo (0x8825) LONG → IFD GPS
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(gpsIfdOffset);
        tiff.putInt(0);
        // IFD GPS
        tiff.putShort((short) 3);
        tiff.putShort((short) 0x0001).putShort((short) 2).putInt(2).put((byte) 'N').put((byte) 0).putShort((short) 0);
        tiff.putShort((short) 0x0002).putShort((short) 5).putInt(3).putInt(latDataOffset);
        tiff.putShort((short) 0x001B).putShort((short) 7).putInt(processing.length).putInt(procDataOffset);
        tiff.putInt(0);
        // 48° 51' 0" N (Paris)
        tiff.putInt(48).putInt(1).putInt(51).putInt(1).putInt(0).putInt(1);
        tiff.put(processing);

        byte[] header = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
        int segLen = 2 + header.length + tiffLen;
        ByteBuffer seg = ByteBuffer.allocate(2 + segLen);
        seg.put((byte) 0xFF).put((byte) 0xE1).putShort((short) segLen).put(header).put(tiff.array());
        return seg.array();
    }
}
