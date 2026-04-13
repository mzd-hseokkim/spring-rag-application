package kr.co.mz.ragservice.conversation;

import kr.co.mz.ragservice.auth.AppUserRepository;
import kr.co.mz.ragservice.model.LlmModel;
import kr.co.mz.ragservice.model.LlmModelRepository;
import kr.co.mz.ragservice.model.ModelClientProvider;
import kr.co.mz.ragservice.model.ModelPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationManagementService {

    private static final Logger log = LoggerFactory.getLogger(ConversationManagementService.class);
    private static final String CONVERSATION_NOT_FOUND = "Conversation not found: ";

    private final ConversationRepository conversationRepository;
    private final LlmModelRepository llmModelRepository;
    private final AppUserRepository appUserRepository;
    private final ModelClientProvider modelClientProvider;
    private final ConversationService conversationService;
    private final SimpMessagingTemplate messagingTemplate;

    public ConversationManagementService(ConversationRepository conversationRepository,
                                         LlmModelRepository llmModelRepository,
                                         AppUserRepository appUserRepository,
                                         ModelClientProvider modelClientProvider,
                                         ConversationService conversationService,
                                         SimpMessagingTemplate messagingTemplate) {
        this.conversationRepository = conversationRepository;
        this.llmModelRepository = llmModelRepository;
        this.appUserRepository = appUserRepository;
        this.modelClientProvider = modelClientProvider;
        this.conversationService = conversationService;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> listAllForUser(UUID userId) {
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationDetailDto getDetail(UUID id) {
        Conversation conv = conversationRepository.findByIdWithModel(id)
                .orElseThrow(() -> new IllegalArgumentException(CONVERSATION_NOT_FOUND + id));
        List<ConversationMessage> messages = conversationService.getHistory(conv.getSessionId());
        return new ConversationDetailDto(toDto(conv), messages);
    }

    @Transactional
    public ConversationDto create(String sessionId, String modelId, UUID userId) {
        Conversation conv = new Conversation(sessionId);
        appUserRepository.findById(userId).ifPresent(conv::setUser);
        if (modelId != null && !modelId.isBlank()) {
            llmModelRepository.findById(UUID.fromString(modelId)).ifPresent(conv::setModel);
        }
        conversationRepository.save(conv);
        return toDto(conv);
    }

    @Transactional
    public Conversation getOrCreate(String sessionId, String modelId, UUID userId) {
        Optional<Conversation> existing = conversationRepository.findBySessionId(sessionId);
        if (existing.isPresent()) return existing.get();

        Conversation conv = new Conversation(sessionId);
        appUserRepository.findById(userId).ifPresent(conv::setUser);
        if (modelId != null && !modelId.isBlank()) {
            llmModelRepository.findById(UUID.fromString(modelId)).ifPresent(conv::setModel);
        }
        return conversationRepository.save(conv);
    }

    @Transactional
    public ConversationDto updateTitle(UUID id, String title) {
        Conversation conv = conversationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(CONVERSATION_NOT_FOUND + id));
        conv.setTitle(title);
        conversationRepository.save(conv);
        return toDto(conv);
    }

    @Transactional
    public void delete(UUID id) {
        Conversation conv = conversationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(CONVERSATION_NOT_FOUND + id));
        conversationService.deleteSession(conv.getSessionId());
        conversationRepository.delete(conv);
    }

    @Async
    @Transactional
    public void generateTitle(String sessionId, String userMessage, String assistantResponse) {
        try {
            Conversation conv = conversationRepository.findBySessionId(sessionId).orElse(null);
            if (conv == null || conv.getTitle() != null) return;

            String prompt = """
                    다음 대화의 제목을 한 줄로 짧게 생성해주세요. 제목만 출력하세요.

                    사용자: %s
                    AI: %s
                    """.formatted(
                    truncate(userMessage, 300),
                    truncate(assistantResponse, 300));

            String title = modelClientProvider.getChatClient(ModelPurpose.QUERY).prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (title != null && !title.isBlank()) {
                conv.setTitle(title.strip());
                conversationRepository.save(conv);

                // WebSocket으로 제목 생성 알림 전송
                String userId = conv.getUser() != null ? conv.getUser().getId().toString() : null;
                if (userId != null) {
                    messagingTemplate.convertAndSendToUser(userId, "/queue/chat",
                            java.util.Map.of(
                                    "type", "title_generated",
                                    "sessionId", sessionId,
                                    "title", title.strip()
                            ));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to generate conversation title for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Transactional
    public void touch(String sessionId) {
        conversationRepository.findBySessionId(sessionId).ifPresent(conv -> {
            conv.preUpdate();
            conversationRepository.save(conv);
        });
    }

    private ConversationDto toDto(Conversation conv) {
        LlmModel model = conv.getModel();
        return new ConversationDto(
                conv.getId(),
                conv.getSessionId(),
                conv.getTitle(),
                model != null ? model.getDisplayName() : null,
                conv.getCreatedAt(),
                conv.getUpdatedAt()
        );
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    public record ConversationDto(
            UUID id,
            String sessionId,
            String title,
            String modelName,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt
    ) {}

    public record ConversationDetailDto(
            ConversationDto conversation,
            List<ConversationMessage> messages
    ) {}
}
