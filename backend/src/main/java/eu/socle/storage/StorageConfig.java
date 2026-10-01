// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import eu.socle.document.DocumentVersionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class StorageConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "socle.storage.provider", havingValue = "git")
    DocumentStore gitDocumentStore(
            DocumentVersionRepository versionRepository,
            StorageProperties properties
    ) {
        return new GitDocumentStore(
                versionRepository,
                Path.of(properties.getGitRepositoryPath()).toAbsolutePath().normalize()
        );
    }

    @Bean
    @ConditionalOnProperty(name = "socle.storage.provider", havingValue = "relational", matchIfMissing = false)
    DocumentStore relationalDocumentStore(DocumentVersionRepository versionRepository) {
        return new RelationalDocumentStore(versionRepository);
    }
}
