package kr.co.mz.ragservice.auth;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    Page<AppUser> findByEmailContainingIgnoreCaseOrNameContainingIgnoreCase(
            String email, String name, Pageable pageable);
}
