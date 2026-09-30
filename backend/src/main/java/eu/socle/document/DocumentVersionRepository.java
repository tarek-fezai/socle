package eu.socle.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersionEntity, UUID> {

    Page<DocumentVersionEntity> findByDocumentIdOrderByVersionNoDesc(UUID documentId, Pageable pageable);

    Optional<DocumentVersionEntity> findByDocumentIdAndVersionNo(UUID documentId, int versionNo);

    long countByDocumentId(UUID documentId);
}
