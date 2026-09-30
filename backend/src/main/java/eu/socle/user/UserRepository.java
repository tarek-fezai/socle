package eu.socle.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    List<UserEntity> findAllByEmailIgnoreCase(String email);

    Optional<UserEntity> findFirstByEmailIgnoreCase(String email);
}
