package com.example.rag.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationManagementService managementService;

    public ConversationController(ConversationManagementService managementService) {
        this.managementService = managementService;
    }

    @GetMapping
    public List<ConversationManagementService.ConversationDto> list(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return managementService.listAllForUser(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationManagementService.ConversationDto create(@RequestBody CreateRequest request,
                                                                 Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return managementService.create(request.sessionId(), request.modelId(), userId);
    }

    @GetMapping("/{id}")
    public ConversationManagementService.ConversationDetailDto getDetail(@PathVariable UUID id) {
        return managementService.getDetail(id);
    }

    @PatchMapping("/{id}/title")
    public ConversationManagementService.ConversationDto updateTitle(@PathVariable UUID id,
                                                                     @RequestBody Map<String, String> body) {
        return managementService.updateTitle(id, body.get("title"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        managementService.delete(id);
    }

    record CreateRequest(String sessionId, String modelId) {}
}
