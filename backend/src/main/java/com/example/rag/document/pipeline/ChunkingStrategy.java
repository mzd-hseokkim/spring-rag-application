package com.example.rag.document.pipeline;

import java.util.List;

public interface ChunkingStrategy {

    List<String> chunk(String text);
}
