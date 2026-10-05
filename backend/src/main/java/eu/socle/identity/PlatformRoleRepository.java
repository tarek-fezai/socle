// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlatformRoleRepository extends JpaRepository<PlatformRoleEntity, PlatformRoleEntity.Pk> {

    List<PlatformRoleEntity> findByUserId(UUID userId);

    Optional<PlatformRoleEntity> findByUserIdAndRole(UUID userId, String role);

    long countByRole(String role);

    /** Titulaires du rôle dont le compte n'est pas {@code disabled}. */
    @Query("""
            SELECT COUNT(p) FROM PlatformRoleEntity p
            WHERE p.role = :role
              AND EXISTS (SELECT 1 FROM UserEntity u WHERE u.id = p.userId AND u.status <> 'disabled')
            """)
    long countEnabledByRole(@Param("role") String role);

    void deleteByUserIdAndRole(UUID userId, String role);
}
