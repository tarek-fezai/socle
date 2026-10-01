// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import eu.socle.document.DocumentVersionRepository;

import java.nio.file.Path;

class GitDocumentStoreContractTest extends DocumentStoreContractTest {

    @Override
    DocumentStore createStore(DocumentVersionRepository versions, Path tempDir) {
        return new GitDocumentStore(versions, tempDir.resolve("repo"));
    }
}
