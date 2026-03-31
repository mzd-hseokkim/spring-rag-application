package com.example.rag.generation.workflow;

import java.util.List;

public record LeafSection(String key, String title, String description, List<String> requirementIds) {}
