package kr.co.mz.ragservice.generation.workflow;

import java.util.List;

public record LeafSection(String key, String title, String description, List<String> requirementIds, String parentPath) {
    public LeafSection(String key, String title, String description, List<String> requirementIds) {
        this(key, title, description, requirementIds, "");
    }
}
