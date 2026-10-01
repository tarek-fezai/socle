// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserIdentityRepository extends JpaRepository<UserIdentityEntity, UserIdentityEntity.Pk> {

    Optional<UserIdentityEntity> findByIssuerAndSubject(String issuer, String subject);

    List<UserIdentityEntity> findByUserId(UUID userId);

    long countByUserId(UUID userId);

    boolean existsByUserIdAndIssuer(UUID userId, String issuer);

    Optional<UserIdentityEntity> findByUserIdAndIssuerAndSubject(UUID userId, String issuer, String subject);

    @Query("SELECT i FROM UserIdentityEntity i WHERE i.subject = :subject")
    List<UserIdentityEntity> findAllBySubject(String subject);
}
