// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlatformRoleRepository extends JpaRepository<PlatformRoleEntity, PlatformRoleEntity.Pk> {

    List<PlatformRoleEntity> findByUserId(UUID userId);

    Optional<PlatformRoleEntity> findByUserIdAndRole(UUID userId, String role);

    long countByRole(String role);

    void deleteByUserIdAndRole(UUID userId, String role);
}
