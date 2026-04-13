package kr.co.mz.ragservice.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessageEntity, Long> {

    List<ConversationMessageEntity> findByConversationIdOrderByIdAsc(UUID conversationId);

    void deleteByConversationId(UUID conversationId);
}
