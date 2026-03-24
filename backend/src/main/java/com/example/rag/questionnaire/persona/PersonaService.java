package com.example.rag.questionnaire.persona;

import com.example.rag.auth.AppUser;
import com.example.rag.auth.AppUserRepository;
import com.example.rag.questionnaire.persona.dto.PersonaRequest;
import com.example.rag.questionnaire.persona.dto.PersonaResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PersonaService {

    private final PersonaRepository personaRepository;
    private final AppUserRepository userRepository;
    private final PersonaPromptGenerator promptGenerator;

    public PersonaService(PersonaRepository personaRepository, AppUserRepository userRepository,
                          PersonaPromptGenerator promptGenerator) {
        this.personaRepository = personaRepository;
        this.userRepository = userRepository;
        this.promptGenerator = promptGenerator;
    }

    public List<PersonaResponse> getAccessiblePersonas(UUID userId) {
        return personaRepository.findAccessibleByUserId(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public PersonaResponse createPersona(PersonaRequest request, UUID userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        String generatedPrompt = promptGenerator.generate(
                request.name(), request.role(), request.focusAreas());

        Persona persona = new Persona(request.name(), request.role(), request.focusAreas(), generatedPrompt, user);
        persona = personaRepository.save(persona);
        return toResponse(persona);
    }

    @Transactional
    public PersonaResponse updatePersona(UUID id, PersonaRequest request, UUID userId) {
        Persona persona = personaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Persona not found: " + id));
        // 사용자 생성 페르소나는 본인만 수정 가능
        if (!persona.isDefault() && (persona.getUser() == null || !persona.getUser().getId().equals(userId))) {
            throw new IllegalStateException("본인이 생성한 페르소나만 수정할 수 있습니다.");
        }
        // 기본 페르소나: 프롬프트와 관심분야만 편집 허용 (이름/역할은 유지)
        if (persona.isDefault()) {
            persona.setFocusAreas(request.focusAreas());
            persona.setPrompt(request.prompt());
        } else {
            persona.setName(request.name());
            persona.setRole(request.role());
            persona.setFocusAreas(request.focusAreas());
            persona.setPrompt(request.prompt());
        }
        return toResponse(persona);
    }

    /**
     * AI로 페르소나 프롬프트를 (재)생성한다.
     */
    @Transactional
    public PersonaResponse regeneratePrompt(UUID id) {
        Persona persona = personaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Persona not found: " + id));
        String generated = promptGenerator.generate(persona.getName(), persona.getRole(), persona.getFocusAreas());
        persona.setPrompt(generated);
        return toResponse(persona);
    }

    @Transactional
    public void deletePersona(UUID id, UUID userId) {
        Persona persona = personaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Persona not found: " + id));
        if (persona.isDefault()) {
            throw new IllegalStateException("기본 페르소나는 삭제할 수 없습니다.");
        }
        if (persona.getUser() == null || !persona.getUser().getId().equals(userId)) {
            throw new IllegalStateException("본인이 생성한 페르소나만 삭제할 수 있습니다.");
        }
        personaRepository.delete(persona);
    }

    private PersonaResponse toResponse(Persona persona) {
        return new PersonaResponse(
                persona.getId(),
                persona.getName(),
                persona.getRole(),
                persona.getFocusAreas(),
                persona.getPrompt(),
                persona.isDefault(),
                persona.getCreatedAt(),
                persona.getUpdatedAt()
        );
    }
}
