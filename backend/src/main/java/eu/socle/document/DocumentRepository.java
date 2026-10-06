// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    List<DocumentEntity> findAllByOrderByUpdatedAtDesc();

    @Query("SELECT d FROM DocumentEntity d WHERE d.id IN :ids AND d.deletedAt IS NULL")
    List<DocumentEntity> findAllActiveByIdIn(@Param("ids") Collection<UUID> ids);

    @Query("SELECT d FROM DocumentEntity d WHERE d.spaceId = :spaceId AND d.deletedAt IS NULL")
    List<DocumentEntity> findActiveBySpaceId(@Param("spaceId") UUID spaceId);

    @Query("SELECT d FROM DocumentEntity d WHERE d.id = :id AND d.deletedAt IS NULL")
    Optional<DocumentEntity> findActiveById(@Param("id") UUID id);

    /** Verrou pessimiste pour update — sérialise les écritures concurrentes sur la même row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DocumentEntity d WHERE d.id = :id AND d.deletedAt IS NULL")
    Optional<DocumentEntity> findActiveByIdForUpdate(@Param("id") UUID id);
}
