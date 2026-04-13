package kr.co.mz.ragservice.document.pipeline;

import java.util.List;

public interface ChunkingStrategy {

    List<String> chunk(String text);
}
