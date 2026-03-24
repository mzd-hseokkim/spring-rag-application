package com.example.rag.questionnaire.persona;

import com.example.rag.questionnaire.persona.dto.PersonaRequest;
import com.example.rag.questionnaire.persona.dto.PersonaResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/personas")
public class PersonaController {

    private final PersonaService personaService;

    public PersonaController(PersonaService personaService) {
        this.personaService = personaService;
    }

    @GetMapping
    public List<PersonaResponse> list(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return personaService.getAccessiblePersonas(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonaResponse create(@RequestBody PersonaRequest request, Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return personaService.createPersona(request, userId);
    }

    @PutMapping("/{id}")
    public PersonaResponse update(@PathVariable UUID id, @RequestBody PersonaRequest request, Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return personaService.updatePersona(id, request, userId);
    }

    @PostMapping("/{id}/regenerate-prompt")
    public PersonaResponse regeneratePrompt(@PathVariable UUID id) {
        return personaService.regeneratePrompt(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        personaService.deletePersona(id, userId);
    }
}
