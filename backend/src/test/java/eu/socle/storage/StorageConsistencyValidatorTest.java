// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Les contrôles d'intégrité du stockage comptent aussi les pages de dossier. */
@ExtendWith(MockitoExtension.class)
class StorageConsistencyValidatorTest {

    @Mock JdbcTemplate jdbc;
    @Mock ApplicationArguments args;

    @Test
    void relationalAndGit_countEveryActiveDocument_includingFolderPages() {
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(), eq(Integer.class))).thenReturn(0);

        new RelationalStorageConsistencyValidator(jdbc).run(args);
        new GitStorageConsistencyValidator(jdbc).run(args);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).queryForObject(sql.capture(), eq(Integer.class));
        assertThat(sql.getAllValues()).allSatisfy(q -> {
            assertThat(q).contains("FROM documents");
            assertThat(q).doesNotContain("page_document_id");
        });
    }
}
