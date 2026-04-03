package com.example.rag.admin;

import com.example.rag.auth.AppUser;
import com.example.rag.auth.AppUserRepository;
import com.example.rag.auth.RefreshTokenRepository;
import com.example.rag.auth.UserRole;
import com.example.rag.conversation.Conversation;
import com.example.rag.conversation.ConversationMessage;
import com.example.rag.conversation.ConversationRepository;
import com.example.rag.conversation.ConversationService;
import com.example.rag.document.Document;
import com.example.rag.document.DocumentRepository;
import com.example.rag.generation.GenerationJob;
import com.example.rag.generation.GenerationJobRepository;
import com.example.rag.generation.GenerationStatus;
import com.example.rag.questionnaire.QuestionnaireJob;
import com.example.rag.questionnaire.QuestionnaireJobRepository;
import com.example.rag.questionnaire.QuestionnaireStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AdminService {

    private final AppUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final DocumentRepository documentRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final GenerationJobRepository generationJobRepository;
    private final QuestionnaireJobRepository questionnaireJobRepository;

    public AdminService(AppUserRepository userRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        DocumentRepository documentRepository,
                        ConversationRepository conversationRepository,
                        ConversationService conversationService,
                        GenerationJobRepository generationJobRepository,
                        QuestionnaireJobRepository questionnaireJobRepository) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.documentRepository = documentRepository;
        this.conversationRepository = conversationRepository;
        this.conversationService = conversationService;
        this.generationJobRepository = generationJobRepository;
        this.questionnaireJobRepository = questionnaireJobRepository;
    }

    // --- 사용자 관리 ---

    @Transactional(readOnly = true)
    public Page<AdminUserDto> listUsers(String query, Pageable pageable) {
        Page<AppUser> page = (query == null || query.isBlank())
                ? userRepository.findAll(pageable)
                : userRepository.findByEmailContainingIgnoreCaseOrNameContainingIgnoreCase(query, query, pageable);
        return page.map(AdminUserDto::from);
    }

    @Transactional
    public AdminUserDto updateRole(UUID userId, UserRole role, UUID currentUserId) {
        if (userId.equals(currentUserId)) {
            throw new IllegalArgumentException("자신의 역할은 변경할 수 없습니다.");
        }
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.setRole(role);
        userRepository.save(user);
        return AdminUserDto.from(user);
    }

    @Transactional
    public void deleteUser(UUID userId, UUID currentUserId) {
        if (userId.equals(currentUserId)) {
            throw new IllegalArgumentException("자신의 계정은 삭제할 수 없습니다.");
        }
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("사용자를 찾을 수 없습니다.");
        }
        refreshTokenRepository.deleteByUserId(userId);
        userRepository.deleteById(userId);
    }

    // --- 문서 관리 ---

    @Transactional(readOnly = true)
    public Page<AdminDocumentDto> listDocuments(Pageable pageable) {
        return documentRepository.findAllWithUser(pageable).map(AdminDocumentDto::from);
    }

    @Transactional
    public AdminDocumentDto updatePublic(UUID documentId, boolean isPublic) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("문서를 찾을 수 없습니다."));
        doc.setPublic(isPublic);
        documentRepository.save(doc);
        return AdminDocumentDto.from(doc);
    }

    @Transactional
    public void deleteDocument(UUID documentId) {
        if (!documentRepository.existsById(documentId)) {
            throw new IllegalArgumentException("문서를 찾을 수 없습니다.");
        }
        documentRepository.deleteById(documentId);
    }

    // --- 대화 관리 ---

    @Transactional(readOnly = true)
    public Page<AdminConversationDto> listConversations(Pageable pageable) {
        return conversationRepository.findAllForAdmin(pageable).map(AdminConversationDto::from);
    }

    @Transactional(readOnly = true)
    public AdminConversationDetailDto getConversationDetail(UUID conversationId) {
        Conversation conv = conversationRepository.findByIdWithModel(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("대화를 찾을 수 없습니다."));
        List<ConversationMessage> messages = conversationService.getHistory(conv.getSessionId());
        return new AdminConversationDetailDto(AdminConversationDto.from(conv), messages);
    }

    @Transactional
    public void deleteConversation(UUID conversationId) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("대화를 찾을 수 없습니다."));
        conversationService.deleteSession(conv.getSessionId());
        conversationRepository.delete(conv);
    }

    // --- DTO ---

    public record AdminUserDto(UUID id, String email, String name, UserRole role,
                                java.time.LocalDateTime createdAt) {
        public static AdminUserDto from(AppUser u) {
            return new AdminUserDto(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getCreatedAt());
        }
    }

    public record AdminDocumentDto(UUID id, String filename, String status, int chunkCount,
                                    boolean isPublic, String ownerEmail,
                                    java.time.LocalDateTime createdAt) {
        public static AdminDocumentDto from(Document d) {
            return new AdminDocumentDto(d.getId(), d.getFilename(), d.getStatus().name(),
                    d.getChunkCount(), d.isPublic(),
                    d.getUser() != null ? d.getUser().getEmail() : null,
                    d.getCreatedAt());
        }
    }

    public record AdminConversationDto(UUID id, String title, String ownerEmail, String modelName,
                                        int messageCount, java.time.LocalDateTime updatedAt) {
        public static AdminConversationDto from(Conversation c) {
            return new AdminConversationDto(c.getId(), c.getTitle(),
                    c.getUser() != null ? c.getUser().getEmail() : null,
                    c.getModel() != null ? c.getModel().getDisplayName() : null,
                    0, // 메시지 수는 별도 조회 필요하므로 0으로 설정
                    c.getUpdatedAt());
        }
    }

    public record AdminConversationDetailDto(AdminConversationDto conversation,
                                              List<ConversationMessage> messages) {}

    // --- 생성 작업 관리 ---

    @Transactional(readOnly = true)
    public Page<AdminGenerationJobDto> listGenerationJobs(String status, Pageable pageable) {
        Page<GenerationJob> page = (status == null || status.isBlank())
                ? generationJobRepository.findAllWithUser(pageable)
                : generationJobRepository.findAllWithUserByStatus(GenerationStatus.valueOf(status), pageable);
        return page.map(AdminGenerationJobDto::from);
    }

    @Transactional
    public void deleteGenerationJob(UUID jobId) {
        if (!generationJobRepository.existsById(jobId)) {
            throw new IllegalArgumentException("생성 작업을 찾을 수 없습니다.");
        }
        generationJobRepository.deleteById(jobId);
    }

    @Transactional(readOnly = true)
    public Page<AdminQuestionnaireJobDto> listQuestionnaireJobs(String status, Pageable pageable) {
        Page<QuestionnaireJob> page = (status == null || status.isBlank())
                ? questionnaireJobRepository.findAllWithUser(pageable)
                : questionnaireJobRepository.findAllWithUserByStatus(QuestionnaireStatus.valueOf(status), pageable);
        return page.map(AdminQuestionnaireJobDto::from);
    }

    @Transactional
    public void deleteQuestionnaireJob(UUID jobId) {
        if (!questionnaireJobRepository.existsById(jobId)) {
            throw new IllegalArgumentException("질문 생성 작업을 찾을 수 없습니다.");
        }
        questionnaireJobRepository.deleteById(jobId);
    }

    public record AdminGenerationJobDto(UUID id, String title, String status, String ownerEmail,
                                         String errorMessage, LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static AdminGenerationJobDto from(GenerationJob j) {
            return new AdminGenerationJobDto(j.getId(), j.getTitle(), j.getStatus().name(),
                    j.getUser() != null ? j.getUser().getEmail() : null,
                    j.getErrorMessage(), j.getCreatedAt(), j.getUpdatedAt());
        }
    }

    public record AdminQuestionnaireJobDto(UUID id, String title, String status, String ownerEmail,
                                            String errorMessage, LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static AdminQuestionnaireJobDto from(QuestionnaireJob j) {
            return new AdminQuestionnaireJobDto(j.getId(), j.getTitle(), j.getStatus().name(),
                    j.getUser() != null ? j.getUser().getEmail() : null,
                    j.getErrorMessage(), j.getCreatedAt(), j.getUpdatedAt());
        }
    }
}
