package kr.co.mz.ragservice.generation.template;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, UUID> {

    @Query("SELECT t FROM DocumentTemplate t WHERE t.isPublic = true OR t.user.id = :userId")
    List<DocumentTemplate> findAvailableTemplates(UUID userId);

    List<DocumentTemplate> findByUserId(UUID userId);
}
