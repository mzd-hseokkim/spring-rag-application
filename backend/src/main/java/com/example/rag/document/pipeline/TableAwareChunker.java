package com.example.rag.document.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 텍스트에서 표를 분리하여 보존한 채로 청킹한다.
 * - <!-- TABLE --> 블록과 마크다운 표(| ... |)를 감지
 * - 표는 분할하지 않고 하나의 청크로 유지
 * - 비표 텍스트는 기존 ChunkingStrategy로 분할
 */
public class TableAwareChunker {

    // PDF 파서가 삽입한 <!-- TABLE --> 블록
    private static final Pattern HTML_TABLE = Pattern.compile(
            "<!-- TABLE \\d+ -->\n(.*?)\n<!-- /TABLE -->", Pattern.DOTALL);

    // 마크다운 표: |로 시작하는 연속 행 (2행 이상)
    private static final Pattern MD_TABLE = Pattern.compile(
            "((?:^\\|.+\\|[ \\t]*\\n){2,}+)", Pattern.MULTILINE);

    /**
     * 텍스트를 표와 비표 세그먼트로 분리한다.
     * 각 세그먼트는 (content, isTable) 쌍이다.
     */
    public static List<Segment> splitSegments(String text) {
        List<Segment> segments = new ArrayList<>();
        List<int[]> tableRanges = new ArrayList<>();

        // HTML 표 블록 감지
        Matcher htmlMatcher = HTML_TABLE.matcher(text);
        while (htmlMatcher.find()) {
            tableRanges.add(new int[]{htmlMatcher.start(), htmlMatcher.end()});
        }

        // 마크다운 표 감지 (HTML 블록과 겹치지 않는 것만)
        addNonOverlappingMdTables(text, tableRanges);

        // 범위 정렬
        tableRanges.sort((a, b) -> Integer.compare(a[0], b[0]));

        int pos = 0;
        for (int[] range : tableRanges) {
            if (range[0] > pos) {
                String before = text.substring(pos, range[0]).trim();
                if (!before.isEmpty()) {
                    segments.add(new Segment(before, false));
                }
            }
            String tableContent = text.substring(range[0], range[1]).trim();
            // HTML 주석 제거하여 순수 마크다운 표만 남김
            tableContent = tableContent
                    .replaceAll("<!-- TABLE \\d+ -->\\n?", "")
                    .replaceAll("\\n?<!-- /TABLE -->", "")
                    .trim();
            if (!tableContent.isEmpty()) {
                segments.add(new Segment(tableContent, true));
            }
            pos = range[1];
        }

        if (pos < text.length()) {
            String remaining = text.substring(pos).trim();
            if (!remaining.isEmpty()) {
                segments.add(new Segment(remaining, false));
            }
        }

        return segments;
    }

    private static void addNonOverlappingMdTables(String text, List<int[]> tableRanges) {
        Matcher mdMatcher = MD_TABLE.matcher(text);
        while (mdMatcher.find()) {
            int start = mdMatcher.start();
            int end = mdMatcher.end();
            boolean overlaps = tableRanges.stream()
                    .anyMatch(r -> start < r[1] && end > r[0]);
            if (!overlaps) {
                tableRanges.add(new int[]{start, end});
            }
        }
    }

    public record Segment(String content, boolean isTable) {}
}
