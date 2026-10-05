// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.blob;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class BlobConfig {

    @Bean
    @ConditionalOnProperty(name = "socle.blob.provider", havingValue = "local", matchIfMissing = true)
    BlobStore localBlobStore(BlobProperties properties) {
        return new LocalBlobStore(Path.of(properties.getLocal().getDir()));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "socle.blob.provider", havingValue = "s3")
    BlobStore s3BlobStore(BlobProperties properties) {
        return new S3BlobStore(properties.getS3());
    }
}
