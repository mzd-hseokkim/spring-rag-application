package kr.co.mz.ragservice.questionnaire.persona;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface PersonaRepository extends JpaRepository<Persona, UUID> {

    @Query("SELECT p FROM Persona p WHERE p.isDefault = true OR p.user.id = :userId ORDER BY p.isDefault DESC, p.createdAt ASC")
    List<Persona> findAccessibleByUserId(UUID userId);

    List<Persona> findByIsDefaultTrue();
}
