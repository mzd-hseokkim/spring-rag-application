package com.example.rag.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ModelPricingRepository extends JpaRepository<ModelPricingEntity, UUID> {
    Optional<ModelPricingEntity> findByModelName(String modelName);
}
