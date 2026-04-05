package com.example.rag.generation.renderer;

import com.example.rag.common.RagException;
import com.example.rag.generation.dto.OutlineNode;
import com.example.rag.generation.template.DocumentTemplate;
import com.example.rag.generation.workflow.DocumentOutline;
import com.example.rag.generation.workflow.SectionContent;
import com.example.rag.generation.workflow.SectionPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class HtmlRenderer implements Renderer {

    private final TemplateEngine templateEngine;
    private final MarkdownConverter markdownConverter;
    private final Path storageBase;

    public HtmlRenderer(TemplateEngine templateEngine,
                        MarkdownConverter markdownConverter,
                        @Value("${app.upload.generation-dir:./uploads/generations}") String storagePath) {
        this.templateEngine = templateEngine;
        this.markdownConverter = markdownConverter;
        this.storageBase = Path.of(storagePath);
    }

    @Override
    public String render(DocumentTemplate template, DocumentOutline outline,
                         List<SectionContent> sections, UUID userId, UUID jobId) {
        // OutlineNode 트리에서 depth별 제목 맵 구성
        List<OutlineNode> outlineNodes = outline.outlineNodes() != null
                ? outline.outlineNodes() : List.of();

        // key → 조상 경로 맵 (breadcrumb용)
        Map<String, List<String>> ancestorMap = new LinkedHashMap<>();
        buildAncestorMap(outlineNodes, List.of(), ancestorMap);

        // 1 depth = 트리의 루트 노드들 (TOC + 간지용)
        List<SectionPlan> tocPlans = outlineNodes.stream()
                .map(n -> new SectionPlan(n.key(), n.title(), n.description(), List.of(), 0))
                .toList();

        // breadcrumb 계산
        List<SectionViewModel> viewModels = sections.stream()
                .map(s -> new SectionViewModel(
                        s.key(),
                        s.title(),
                        markdownConverter.toHtml(s.content()),
                        s.highlights(),
                        s.tables(),
                        s.references(),
                        s.layoutType() != null ? s.layoutType() : "TEXT_FULL",
                        convertLayoutDataNewlines(s.layoutData()),
                        s.governingMessage() != null ? s.governingMessage() : "",
                        s.visualGuide() != null ? s.visualGuide() : "",
                        buildBreadcrumb(s.key(), ancestorMap)))
                .toList();

        // chapters 구성 (간지용): 1depth 노드 + 직계 2depth children
        List<ChapterViewModel> chapters = buildChapters(outlineNodes);

        List<String> allReferences = sections.stream()
                .flatMap(s -> s.references().stream())
                .distinct()
                .map(ref -> ref.replaceAll("(https?://[^\\s)]+)",
                        "<a href=\"$1\" target=\"_blank\" rel=\"noopener noreferrer\" style=\"color:#2563eb;text-decoration:underline\">$1</a>"))
                .toList();

        Context ctx = new Context();
        // sections를 outline 트리 순서로 정렬
        List<String> leafOrder = new ArrayList<>();
        collectLeafKeyOrder(outlineNodes, leafOrder);
        Map<String, Integer> orderIndex = new HashMap<>();
        for (int i = 0; i < leafOrder.size(); i++) orderIndex.put(leafOrder.get(i), i);
        List<SectionViewModel> sortedViewModels = new ArrayList<>(viewModels);
        sortedViewModels.sort((a, b) -> {
            int ia = orderIndex.getOrDefault(a.key(), Integer.MAX_VALUE);
            int ib = orderIndex.getOrDefault(b.key(), Integer.MAX_VALUE);
            return Integer.compare(ia, ib);
        });

        // 내용이 있는 chapter만 필터 (간지)
        java.util.Set<String> sectionKeySet = sortedViewModels.stream()
                .map(SectionViewModel::key)
                .collect(java.util.stream.Collectors.toSet());
        List<ChapterViewModel> activeChapters = chapters.stream()
                .filter(ch -> sectionKeySet.stream().anyMatch(sk -> sk.startsWith(ch.key() + ".") || sk.equals(ch.key())))
                .toList();

        ctx.setVariable("outline", outline);
        ctx.setVariable("sections", sortedViewModels);
        ctx.setVariable("tocSections", tocPlans);
        ctx.setVariable("chapters", activeChapters);
        ctx.setVariable("allReferences", allReferences);
        ctx.setVariable("generatedAt", LocalDateTime.now());

        String templatePath = template.getTemplatePath();
        if (templatePath == null || templatePath.isBlank()) {
            templatePath = "generation/proposal";
        }
        String html = templateEngine.process(templatePath, ctx);

        Path outputDir = storageBase.resolve(userId.toString());
        try {
            Files.createDirectories(outputDir);
            Path outputFile = outputDir.resolve(jobId + ".html");
            Files.writeString(outputFile, html);
            return outputFile.toString();
        } catch (IOException e) {
            throw new RagException("Failed to write generated HTML file", e);
        }
    }

    /** outline 트리의 leaf key를 트리 순서대로 수집 */
    private void collectLeafKeyOrder(List<OutlineNode> nodes, List<String> result) {
        for (OutlineNode node : nodes) {
            if (node.children().isEmpty()) {
                result.add(node.key());
            } else {
                collectLeafKeyOrder(node.children(), result);
            }
        }
    }

    /**
     * OutlineNode 트리를 순회하여 각 노드의 key → 조상 "key. title" 리스트 맵을 구성.
     * 예: "I.1.1.1" → ["I. 일반현황", "1. 제안사 일반현황", "I.1.1 하도급 범위"]
     */
    private void buildAncestorMap(List<OutlineNode> nodes, List<String> ancestors,
                                   Map<String, List<String>> result) {
        for (OutlineNode node : nodes) {
            result.put(node.key(), ancestors);
            if (!node.children().isEmpty()) {
                List<String> childAncestors = new ArrayList<>(ancestors);
                childAncestors.add(node.key() + ". " + node.title());
                buildAncestorMap(node.children(), childAncestors, result);
            }
        }
    }

    /**
     * 섹션 key의 조상 경로를 breadcrumb 문자열로 변환.
     * 조상이 없으면 빈 문자열, 있으면 " > "로 연결.
     */
    private String buildBreadcrumb(String key, Map<String, List<String>> ancestorMap) {
        List<String> ancestors = ancestorMap.get(key);
        if (ancestors == null || ancestors.isEmpty()) return "";
        return String.join("  >  ", ancestors);
    }

    /**
     * OutlineNode 트리의 루트 노드(1depth) 기준으로 간지 ViewModel 목록을 구성한다.
     * 각 chapter에는 직계 children(2depth) 항목 목록이 포함된다.
     */
    private List<ChapterViewModel> buildChapters(List<OutlineNode> outlineNodes) {
        List<ChapterViewModel> chapters = new ArrayList<>();
        for (OutlineNode node : outlineNodes) {
            List<ChapterViewModel.SubItem> subItems = node.children().stream()
                    .map(child -> new ChapterViewModel.SubItem(child.key(), child.title()))
                    .toList();
            chapters.add(new ChapterViewModel(node.key(), node.title(), subItems));
        }
        return chapters;
    }

    /**
     * layoutData 내 문자열 값의 \n을 <br>로 변환하고, · 구분자를 줄바꿈으로 치환.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertLayoutDataNewlines(Map<String, Object> layoutData) {
        if (layoutData == null || layoutData.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : layoutData.entrySet()) {
            result.put(entry.getKey(), convertValueNewlines(entry.getValue()));
        }
        return result;
    }

    private Object convertValueNewlines(Object value) {
        if (value instanceof String s) {
            return convertStringNewlines(s);
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (var e : map.entrySet()) {
                converted.put(String.valueOf(e.getKey()), convertValueNewlines(e.getValue()));
            }
            return converted;
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) return list;
            // String만 담긴 리스트 → <br>로 연결
            if (list.getFirst() instanceof String) {
                return list.stream()
                        .map(item -> convertStringNewlines((String) item))
                        .collect(java.util.stream.Collectors.joining("<br>"));
            }
            // Map 등 복합 객체 리스트 → 재귀 변환 후 리스트 유지
            return list.stream().map(this::convertValueNewlines).toList();
        }
        return value;
    }

    private String convertStringNewlines(String s) {
        // 이모지 제거
        String result = s.replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2300}-\\x{23FF}\\x{2600}-\\x{27BF}\\x{2B50}-\\x{2BFF}\\x{FE00}-\\x{FE0F}\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}]+\\s?", "");
        // \\n 리터럴과 실제 줄바꿈을 <br>로 변환
        result = result.replace("\\n", "\n").replace("\n", "<br>");
        // 줄 시작 또는 , 뒤의 · / • 만 bullet로 인식하여 개행 (단어 중간의 가운뎃점은 무시)
        result = result.replaceAll("(?<=<br>|^)[,\\s]*[·•]\\s?", "<br>· ");
        result = result.replaceAll(",\\s*[·•]\\s?", "<br>· ");
        // 선행 <br> 중복 제거
        result = result.replace("<br><br>", "<br>");
        // 맨 앞 <br> 제거
        if (result.startsWith("<br>")) result = result.substring(4);
        return result;
    }
}
