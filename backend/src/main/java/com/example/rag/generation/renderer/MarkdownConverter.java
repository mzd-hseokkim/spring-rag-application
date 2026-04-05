package com.example.rag.generation.renderer;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

@Component
public class MarkdownConverter {

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().build();

    public String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        // 이모지 제거 (U+1F000~U+1FAFF, U+2600~U+27BF 등)
        String noEmoji = markdown.replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2300}-\\x{23FF}\\x{2600}-\\x{27BF}\\x{2B50}-\\x{2BFF}\\x{FE00}-\\x{FE0F}\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}]+\\s?", "");
        // · 또는 • 로 시작하는 줄을 마크다운 리스트 항목으로 변환
        String normalized = noEmoji.lines()
                .map(line -> {
                    String trimmed = line.stripLeading();
                    if (trimmed.startsWith("· ") || trimmed.startsWith("• ")) {
                        return "- " + trimmed.substring(2);
                    }
                    return line;
                })
                .collect(java.util.stream.Collectors.joining("\n"));
        Node document = parser.parse(normalized);
        String html = renderer.render(document);
        // commonmark 출력의 불필요한 연속 줄바꿈 정리
        return html.replaceAll("\\n{2,}", "\n").trim();
    }
}
