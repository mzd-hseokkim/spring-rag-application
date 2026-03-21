package com.example.rag.document.tag;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final DocumentTagRepository tagRepository;

    public TagController(DocumentTagRepository tagRepository) {
        this.tagRepository = tagRepository;
    }

    @GetMapping
    public List<DocumentTag> list() {
        return tagRepository.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentTag create(@RequestBody Map<String, String> body) {
        return tagRepository.save(new DocumentTag(body.get("name")));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        tagRepository.deleteById(id);
    }
}
