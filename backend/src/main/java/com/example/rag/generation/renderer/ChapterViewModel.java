package com.example.rag.generation.renderer;

import java.util.List;

/**
 * 간지(chapter divider) 렌더링용 ViewModel.
 * 1 depth 목차 항목 하나에 대응하며, 소속 2 depth 하위 제목 목록을 포함한다.
 */
public record ChapterViewModel(
        String key,
        String title,
        List<SubItem> subItems
) {
    public record SubItem(String key, String title) {}
}
