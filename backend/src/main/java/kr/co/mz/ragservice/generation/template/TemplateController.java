package kr.co.mz.ragservice.generation.template;

import kr.co.mz.ragservice.generation.dto.TemplateRequest;
import kr.co.mz.ragservice.generation.dto.TemplateResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public List<TemplateResponse> list(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return templateService.getAvailableTemplates(userId);
    }

    @GetMapping("/{id}")
    public TemplateResponse get(@PathVariable UUID id) {
        return templateService.getTemplate(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse create(@RequestBody TemplateRequest request, Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return templateService.createTemplate(request, userId);
    }

    @PutMapping("/{id}")
    public TemplateResponse update(@PathVariable UUID id, @RequestBody TemplateRequest request) {
        return templateService.updateTemplate(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        templateService.deleteTemplate(id);
    }
}
