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
    private int orphanRetentionDays = 7;
    private String allowedMediaTypes =
            "image/png,image/jpeg,image/webp,image/gif,application/pdf,"
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

    public int getOrphanRetentionDays() {
        return orphanRetentionDays;
    }

    public void setOrphanRetentionDays(int orphanRetentionDays) {
        this.orphanRetentionDays = orphanRetentionDays;
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
