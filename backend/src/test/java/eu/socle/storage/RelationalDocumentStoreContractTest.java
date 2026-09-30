package eu.socle.storage;

import eu.socle.document.DocumentVersionRepository;

import java.nio.file.Path;

class RelationalDocumentStoreContractTest extends DocumentStoreContractTest {

    @Override
    DocumentStore createStore(DocumentVersionRepository versions, Path tempDir) {
        return new RelationalDocumentStore(versions);
    }
}
