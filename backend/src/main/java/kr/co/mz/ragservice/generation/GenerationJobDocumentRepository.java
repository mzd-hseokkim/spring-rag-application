package kr.co.mz.ragservice.generation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GenerationJobDocumentRepository extends JpaRepository<GenerationJobDocument, GenerationJobDocument.PK> {

    List<GenerationJobDocument> findByJobId(UUID jobId);

    void deleteByJobId(UUID jobId);
}
