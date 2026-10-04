// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConfigurationProperties(prefix = "socle.attachment")
public class AttachmentProperties {

    private int maxMb = 25;
    /** Plafond distinct pour {@code video/mp4} et {@code video/webm}. */
    private int maxVideoMb = 200;
    private int orphanRetentionDays = 7;
    /** Plafond largeur×hauteur avant décodage pixel (anti bombe de décompression). */
    private long maxImagePixels = 40_000_000L;
    private String allowedMediaTypes =
            "image/png,image/jpeg,image/webp,image/gif,application/pdf,"
                    + "video/mp4,video/webm,"
                    + "application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,"
                    + "application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,"
                    + "application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation,"
                    + "text/plain,text/csv,application/zip";

    public int getMaxMb() {
        return maxMb;
    }

    public void setMaxMb(int maxMb) {
        this.maxMb = maxMb;
    }

    public long maxBytes() {
        return Math.max(1, maxMb) * 1024L * 1024L;
    }

    public int getMaxVideoMb() {
        return maxVideoMb;
    }

    public void setMaxVideoMb(int maxVideoMb) {
        this.maxVideoMb = maxVideoMb;
    }

    public long maxVideoBytes() {
        return Math.max(1, maxVideoMb) * 1024L * 1024L;
    }

    /** Limite applicable selon le type MIME détecté. */
    public long maxBytesFor(String mediaType) {
        if (mediaType != null) {
            String base = mediaType.toLowerCase(Locale.ROOT).split(";")[0].trim();
            if ("video/mp4".equals(base) || "video/webm".equals(base)) {
                return maxVideoBytes();
            }
        }
        return maxBytes();
    }

    public int getOrphanRetentionDays() {
        return orphanRetentionDays;
    }

    public void setOrphanRetentionDays(int orphanRetentionDays) {
        this.orphanRetentionDays = orphanRetentionDays;
    }

    public long getMaxImagePixels() {
        return maxImagePixels;
    }

    public void setMaxImagePixels(long maxImagePixels) {
        this.maxImagePixels = maxImagePixels;
    }

    public long maxImagePixels() {
        return Math.max(1L, maxImagePixels);
    }

    public String getAllowedMediaTypes() {
        return allowedMediaTypes;
    }

    public void setAllowedMediaTypes(String allowedMediaTypes) {
        this.allowedMediaTypes = allowedMediaTypes;
    }

    public Set<String> allowedMediaTypeSet() {
        if (allowedMediaTypes == null || allowedMediaTypes.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(allowedMediaTypes.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean isAllowed(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return false;
        }
        String base = mediaType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if ("image/svg+xml".equals(base) || "image/svg".equals(base)) {
            return false;
        }
        return allowedMediaTypeSet().contains(base);
    }
}
