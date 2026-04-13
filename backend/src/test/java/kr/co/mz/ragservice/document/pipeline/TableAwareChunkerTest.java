package kr.co.mz.ragservice.document.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableAwareChunkerTest {

    @Test
    void 표가_없는_텍스트는_하나의_비표_세그먼트() {
        String text = "일반 텍스트입니다.\n\n두 번째 단락입니다.";
        List<TableAwareChunker.Segment> segments = TableAwareChunker.splitSegments(text);

        assertEquals(1, segments.size());
        assertFalse(segments.get(0).isTable());
        assertEquals(text, segments.get(0).content());
    }

    @Test
    void 마크다운_표_감지() {
        String text = """
                서론 텍스트입니다.

                | 이름 | 나이 |
                | --- | --- |
                | 홍길동 | 30 |
                | 김철수 | 25 |

                결론 텍스트입니다.
                """;
        List<TableAwareChunker.Segment> segments = TableAwareChunker.splitSegments(text);

        assertEquals(3, segments.size());
        assertFalse(segments.get(0).isTable());
        assertTrue(segments.get(0).content().contains("서론"));
        assertTrue(segments.get(1).isTable());
        assertTrue(segments.get(1).content().contains("홍길동"));
        assertFalse(segments.get(2).isTable());
        assertTrue(segments.get(2).content().contains("결론"));
    }

    @Test
    void HTML_TABLE_블록_감지() {
        String text = """
                앞부분 텍스트

                <!-- TABLE 1 -->
                | col1 | col2 |
                | --- | --- |
                | a | b |
                <!-- /TABLE -->

                뒷부분 텍스트
                """;
        List<TableAwareChunker.Segment> segments = TableAwareChunker.splitSegments(text);

        assertEquals(3, segments.size());
        assertFalse(segments.get(0).isTable());
        assertTrue(segments.get(1).isTable());
        // HTML 주석은 제거됨
        assertFalse(segments.get(1).content().contains("<!--"));
        assertTrue(segments.get(1).content().contains("col1"));
        assertFalse(segments.get(2).isTable());
    }

    @Test
    void 연속_표_분리() {
        String text = """
                | a | b |
                | --- | --- |
                | 1 | 2 |

                중간 텍스트

                | x | y |
                | --- | --- |
                | 3 | 4 |
                """;
        List<TableAwareChunker.Segment> segments = TableAwareChunker.splitSegments(text);

        assertEquals(3, segments.size());
        assertTrue(segments.get(0).isTable());
        assertFalse(segments.get(1).isTable());
        assertTrue(segments.get(2).isTable());
    }

    @Test
    void 표만_있는_텍스트() {
        String text = """
                | col1 | col2 |
                | --- | --- |
                | val1 | val2 |
                """;
        List<TableAwareChunker.Segment> segments = TableAwareChunker.splitSegments(text);

        assertEquals(1, segments.size());
        assertTrue(segments.get(0).isTable());
    }

    @Test
    void 빈_텍스트() {
        List<TableAwareChunker.Segment> segments = TableAwareChunker.splitSegments("");
        assertTrue(segments.isEmpty());
    }
}
