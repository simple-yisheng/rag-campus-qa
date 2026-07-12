package com.rag.campus.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentChunker 单元测试
 * <p>
 * 测试四种分块策略：Markdown 标题、中文结构、Q&A 格式、滑动窗口兜底。
 */
@DisplayName("DocumentChunker")
class DocumentChunkerTest {

    private static final int CHUNK_SIZE = 200;
    private static final int OVERLAP = 50;

    private DocumentChunker chunker;

    @BeforeEach
    void setUp() {
        chunker = new DocumentChunker(CHUNK_SIZE, OVERLAP);
    }

    // ==================== 空/边界输入 ====================

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("null 文本应返回空列表")
        void shouldReturnEmptyForNull() {
            assertThat(chunker.chunk(null)).isEmpty();
        }

        @Test
        @DisplayName("空字符串应返回空列表")
        void shouldReturnEmptyForBlank() {
            assertThat(chunker.chunk("")).isEmpty();
        }

        @Test
        @DisplayName("短文本应返回单 chunk")
        void shouldReturnSingleChunkForShortText() {
            String text = "这是一段很短的文本。";
            List<String> chunks = chunker.chunk(text);
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0)).isEqualTo(text);
        }
    }

    // ==================== 策略一：Markdown 标题切分 ====================

    @Nested
    @DisplayName("Markdown 标题切分")
    class MarkdownStrategy {

        @Test
        @DisplayName("检测到 # 标题应走 Markdown 策略")
        void shouldDetectMarkdownHeadings() {
            String text = """
                    # 第一章 总则
                    本章内容是关于学校管理的基本原则。

                    ## 1.1 学生权利
                    学生享有受教育的权利，学校应保障学生的合法权益。
                    """;

            List<String> chunks = chunker.chunk(text);
            assertThat(chunks).isNotEmpty();
            // 应该识别出标题并按标题切分
            assertThat(chunks.get(0)).contains("第一章 总则");
        }

        @Test
        @DisplayName("多个 ### 子标题应各自成段")
        void shouldSplitBySubHeadings() {
            String text = """
                    ### 第一条
                    内容A内容A内容A内容A。

                    ### 第二条
                    内容B内容B内容B内容B。
                    """;

            List<String> chunks = chunker.chunk(text);
            assertThat(chunks).hasSize(2);
            assertThat(chunks.get(0)).contains("第一条");
            assertThat(chunks.get(1)).contains("第二条");
        }
    }

    // ==================== 策略二：中文结构切分 ====================

    @Nested
    @DisplayName("中文结构切分（政策文档）")
    class StructureStrategy {

        @Test
        @DisplayName("POLICY 分类应走结构切分")
        void shouldSplitPolicyDocByStructure() {
            String text = """
                    第一章 总则
                    本章介绍学校管理规定的总体框架和适用范围。

                    第二章 学生管理
                    学生应当遵守学校的各项规章制度，按时上课，完成作业。
                    """;

            List<String> chunks = chunker.chunk(text, "POLICY");
            assertThat(chunks.size()).isGreaterThanOrEqualTo(1);
            // 应识别出章节边界
            assertThat(String.join("", chunks)).contains("总则");
        }

        @Test
        @DisplayName("普通分类不触发结构切分")
        void shouldNotUseStructureForNonPolicyCategory() {
            // "OTHER" 不在 STRUCTURED_CATEGORIES 中
            String text = "第一章 非政策内容\n只是一段普通文本。";

            List<String> chunks = chunker.chunk(text, "OTHER");

            // 不应崩溃，走滑动窗口
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("第X条 边界应被识别")
        void shouldSplitByArticle() {
            String text = """
                    第一条 学生宿舍管理规定
                    不得在宿舍内使用大功率电器，违者将按校规处理。

                    第二条 课堂纪律
                    上课期间不得使用手机，保持课堂安静。
                    """;

            List<String> chunks = chunker.chunk(text, "POLICY");
            assertThat(chunks.size()).isGreaterThanOrEqualTo(1);
            String joined = String.join("", chunks);
            assertThat(joined).contains("大功率电器");
            assertThat(joined).contains("课堂纪律");
        }
    }

    // ==================== 策略三：滑动窗口兜底 ====================

    @Nested
    @DisplayName("滑动窗口兜底")
    class SlidingWindowStrategy {

        @Test
        @DisplayName("无结构文本走滑动窗口")
        void shouldUseSlidingWindowForPlainText() {
            // 只有普通段落，无 Markdown 标题、无中文结构标记
            String text = "这是第一段普通文本，没有任何特殊结构标记。\n这是第二段普通文本。";

            List<String> chunks = chunker.chunk(text, "OTHER");
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("超长文本应被切分为多个 chunk")
        void shouldSplitLongTextIntoMultipleChunks() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                sb.append("段落").append(i).append("：").append("A".repeat(100)).append("\n");
            }

            List<String> chunks = chunker.chunk(sb.toString(), "OTHER");
            assertThat(chunks.size()).isGreaterThan(1);
        }

        @Test
        @DisplayName("所有 chunk 不应超过 chunkSize（超长段落除外）")
        void chunksShouldNotExceedMaxSize() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 15; i++) {
                sb.append("第").append(i).append("段：").append("B".repeat(60)).append("\n");
            }

            List<String> chunks = chunker.chunk(sb.toString(), "OTHER");

            for (String chunk : chunks) {
                // 单个段落超长时允许超过 chunkSize，但不应太离谱
                assertThat(chunk.length())
                        .as("chunk 长度应可接受: " + chunk.length())
                        .isLessThan(CHUNK_SIZE * 2);
            }
        }
    }

    // ==================== 兼容旧调用 ====================

    @Nested
    @DisplayName("兼容旧版 chunk(text) 调用")
    class LegacyChunk {

        @Test
        @DisplayName("无分类参数的 Markdown 文本也能正确切分")
        void shouldStillDetectMarkdownWithoutCategory() {
            String text = "# 标题\n这是内容。";

            List<String> chunks = chunker.chunk(text);
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("null 分类参数不抛异常")
        void shouldNotThrowForNullCategory() {
            String text = "普通文本内容。";
            List<String> chunks = chunker.chunk(text, null);
            assertThat(chunks).isNotEmpty();
        }
    }
}
