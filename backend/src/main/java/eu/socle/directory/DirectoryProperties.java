// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.directory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "socle.directory")
public class DirectoryProperties {

    /** Requêtes /directory/users par utilisateur et par minute. */
    private int rateLimitPerMinute = 30;

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }
}
