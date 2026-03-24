package com.example.rag.generation.dto;

import java.util.List;

public record OutlineNode(
        String key,
        String title,
        String description,
        List<OutlineNode> children
) {
    public OutlineNode {
        if (children == null) children = List.of();
    }
}
