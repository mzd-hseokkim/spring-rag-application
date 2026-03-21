package com.example.rag.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    @Query("SELECT c FROM Conversation c LEFT JOIN FETCH c.model WHERE c.sessionId = :sessionId")
    Optional<Conversation> findBySessionId(String sessionId);

    @Query("SELECT c FROM Conversation c LEFT JOIN FETCH c.model WHERE c.user.id = :userId ORDER BY c.updatedAt DESC")
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    @Query("SELECT c FROM Conversation c LEFT JOIN FETCH c.model WHERE c.id = :id")
    Optional<Conversation> findByIdWithModel(UUID id);
}
