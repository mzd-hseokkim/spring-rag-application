package com.example.rag.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LlmModelRepository extends JpaRepository<LlmModel, UUID> {

    List<LlmModel> findByPurposeAndActiveTrue(ModelPurpose purpose);

    Optional<LlmModel> findByPurposeAndDefaultModelTrue(ModelPurpose purpose);

    @Modifying
    @Query("UPDATE LlmModel m SET m.defaultModel = false WHERE m.purpose = :purpose AND m.defaultModel = true")
    void clearDefaultByPurpose(ModelPurpose purpose);
}
