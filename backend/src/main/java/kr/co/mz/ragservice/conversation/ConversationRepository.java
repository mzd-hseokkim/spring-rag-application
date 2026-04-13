package kr.co.mz.ragservice.conversation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Query(value = "SELECT c FROM Conversation c LEFT JOIN FETCH c.model LEFT JOIN FETCH c.user",
            countQuery = "SELECT COUNT(c) FROM Conversation c")
    Page<Conversation> findAllForAdmin(Pageable pageable);
}
