package com.rag.campus.support;

import com.rag.campus.dto.ChatResponse;
import com.rag.campus.entity.Document;
import com.rag.campus.entity.DocumentChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RagPromptTemplate 单元测试
 * <p>
 * 纯逻辑类，无外部依赖，直接测试静态方法。
 */
@DisplayName("RagPromptTemplate")
class RagPromptTemplateTest {

    // ==================== buildSystemPrompt ====================

    @Nested
    @DisplayName("buildSystemPrompt")
    class BuildSystemPrompt {

        @Test
        @DisplayName("应包含角色定位关键词")
        void shouldContainRoleKeywords() {
            String prompt = RagPromptTemplate.buildSystemPrompt();

            assertThat(prompt)
                    .contains("校园智答")
                    .contains("校园知识库")
                    .contains("规章制度");
        }

        @Test
        @DisplayName("应限制仅根据参考资料回答 — 防幻觉")
        void shouldRestrictToProvidedMaterials() {
            String prompt = RagPromptTemplate.buildSystemPrompt();

            assertThat(prompt)
                    .contains("仅根据【参考资料】");
        }

        @Test
        @DisplayName("应定义'知识库无收录'的兜底回复")
        void shouldHaveFallbackForUnknown() {
            String prompt = RagPromptTemplate.buildSystemPrompt();

            assertThat(prompt)
                    .contains("该问题在知识库中暂无收录");
        }

        @Test
        @DisplayName("应要求标注引用来源")
        void shouldRequireCitations() {
            String prompt = RagPromptTemplate.buildSystemPrompt();

            assertThat(prompt)
                    .contains("标注引用");
        }

        @Test
        @DisplayName("每次调用返回相同内容（幂等）")
        void shouldBeIdempotent() {
            String first = RagPromptTemplate.buildSystemPrompt();
            String second = RagPromptTemplate.buildSystemPrompt();

            assertThat(first).isEqualTo(second);
        }
    }

    // ==================== buildUserPrompt ====================

    @Nested
    @DisplayName("buildUserPrompt")
    class BuildUserPrompt {

        @Test
        @DisplayName("应包含用户原始问题")
        void shouldContainOriginalQuestion() {
            String question = "奖学金申请条件是什么？";

            String prompt = RagPromptTemplate.buildUserPrompt(
                    question,
                    List.of(),
                    id -> null,
                    id -> null
            );

            assertThat(prompt).contains(question);
        }

        @Test
        @DisplayName("应包含资料片段编号和文档标题")
        void shouldIncludeChunkNumberAndTitle() {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(1L);
            chunk.setDocumentId(100L);
            chunk.setChunkText("国家奖学金金额为8000元/年。");

            Document doc = new Document();
            doc.setId(100L);
            doc.setTitle("奖学金评定办法");

            VectorStore.Hit hit = new VectorStore.Hit(1L, 0.92f);

            String prompt = RagPromptTemplate.buildUserPrompt(
                    "国家奖学金多少钱？",
                    List.of(hit),
                    id -> id.equals(1L) ? chunk : null,
                    id -> id.equals(100L) ? doc : null
            );

            assertThat(prompt)
                    .contains("资料片段 1")
                    .contains("奖学金评定办法")
                    .contains("8000元/年");
        }

        @Test
        @DisplayName("chunk 为 null 时应跳过该条")
        void shouldSkipNullChunk() {
            Document doc = new Document();
            doc.setId(100L);
            doc.setTitle("测试文档");

            VectorStore.Hit hit = new VectorStore.Hit(1L, 0.5f);

            String prompt = RagPromptTemplate.buildUserPrompt(
                    "测试问题",
                    List.of(hit),
                    id -> null,          // chunk 返回 null
                    id -> doc
            );

            // 不应包含资料片段标记
            assertThat(prompt).doesNotContain("资料片段");
            // 仍应包含用户问题
            assertThat(prompt).contains("测试问题");
        }

        @Test
        @DisplayName("文档为 null 时应显示'未知文档'")
        void shouldFallbackToUnknownDocument() {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(1L);
            chunk.setDocumentId(999L);
            chunk.setChunkText("测试内容。");

            VectorStore.Hit hit = new VectorStore.Hit(1L, 0.8f);

            String prompt = RagPromptTemplate.buildUserPrompt(
                    "测试问题",
                    List.of(hit),
                    id -> chunk,
                    id -> null            // 文档返回 null
            );

            assertThat(prompt).contains("未知文档");
        }

        @Test
        @DisplayName("多个 hits 应全部包含且编号递增")
        void shouldIncludeAllHitsInOrder() {
            DocumentChunk chunk1 = chunkWith(1L, 101L, "内容一");
            DocumentChunk chunk2 = chunkWith(2L, 102L, "内容二");
            Document doc1 = docWith(101L, "文档A");
            Document doc2 = docWith(102L, "文档B");

            String prompt = RagPromptTemplate.buildUserPrompt(
                    "测试",
                    List.of(
                            new VectorStore.Hit(1L, 0.9f),
                            new VectorStore.Hit(2L, 0.7f)
                    ),
                    id -> id == 1L ? chunk1 : (id == 2L ? chunk2 : null),
                    id -> id == 101L ? doc1 : (id == 102L ? doc2 : null)
            );

            assertThat(prompt).contains("资料片段 1");
            assertThat(prompt).contains("资料片段 2");
            assertThat(prompt).contains("文档A");
            assertThat(prompt).contains("文档B");
            // 片段1应在片段2之前
            assertThat(prompt.indexOf("资料片段 1"))
                    .isLessThan(prompt.indexOf("资料片段 2"));
        }

        @Test
        @DisplayName("应包含输出格式要求")
        void shouldIncludeOutputFormatInstructions() {
            String prompt = RagPromptTemplate.buildUserPrompt(
                    "有哪些竞赛？",
                    List.of(),
                    id -> null,
                    id -> null
            );

            assertThat(prompt)
                    .contains("Markdown 表格")
                    .contains("输出格式要求");
        }

        private DocumentChunk chunkWith(Long id, Long docId, String text) {
            DocumentChunk c = new DocumentChunk();
            c.setId(id);
            c.setDocumentId(docId);
            c.setChunkText(text);
            return c;
        }

        private Document docWith(Long id, String title) {
            Document d = new Document();
            d.setId(id);
            d.setTitle(title);
            return d;
        }
    }

    // ==================== buildRewritePrompt ====================

    @Nested
    @DisplayName("buildRewritePrompt")
    class BuildRewritePrompt {

        @Test
        @DisplayName("应包含历史对话和当前问题")
        void shouldContainHistoryAndCurrentQuestion() {
            List<Map<String, String>> history = List.of(
                    Map.of("role", "user", "content", "奖学金有哪些？"),
                    Map.of("role", "assistant", "content", "主要有国家奖学金、校级奖学金等。")
            );

            String prompt = RagPromptTemplate.buildRewritePrompt(history, "申请条件呢？");

            assertThat(prompt)
                    .contains("奖学金有哪些？")
                    .contains("校级奖学金")
                    .contains("申请条件呢？");
        }

        @Test
        @DisplayName("应要求补全省略的指代")
        void shouldAskToCompletePronouns() {
            String prompt = RagPromptTemplate.buildRewritePrompt(
                    List.of(),
                    "那第三章呢"
            );

            assertThat(prompt)
                    .contains("补全省略")
                    .contains("第三章");
        }

        @Test
        @DisplayName("空历史应正常返回包含当前问题的 prompt")
        void shouldWorkWithEmptyHistory() {
            String prompt = RagPromptTemplate.buildRewritePrompt(
                    Collections.emptyList(),
                    "助学金申请流程？"
            );

            assertThat(prompt).contains("助学金申请流程？");
        }

        @Test
        @DisplayName("已完整独立的问题应返回原句")
        void shouldReturnOriginalIfComplete() {
            String prompt = RagPromptTemplate.buildRewritePrompt(
                    Collections.emptyList(),
                    "国家奖学金的申请条件是什么？需要什么材料？"
            );

            // 应包含"直接返回原句"的指令
            assertThat(prompt).contains("直接返回原句");
        }
    }

    // ==================== buildSources ====================

    @Nested
    @DisplayName("buildSources")
    class BuildSources {

        @Test
        @DisplayName("应按文档去重，同文档保留最高分")
        void shouldDeduplicateByDocumentKeepHighestScore() {
            DocumentChunk chunk1 = chunk(1L, 100L, "高分内容AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKKKKLLLLMMMMNNNNOOOOPPPPQQQQRRRRSSSSTTTTUUUUVVVV");
            DocumentChunk chunk2 = chunk(2L, 100L, "低分内容");  // 同文档
            Document doc = doc(100L, "文档A");

            List<ChatResponse.SourceInfo> sources = RagPromptTemplate.buildSources(
                    List.of(
                            new VectorStore.Hit(1L, 0.6f),   // 低分先来
                            new VectorStore.Hit(2L, 0.9f)    // 高分后来
                    ),
                    id -> id == 1L ? chunk1 : (id == 2L ? chunk2 : null),
                    id -> doc
            );

            // 同文档只保留最高分
            assertThat(sources).hasSize(1);
            // float → double 转换有精度损失，用 offset 比较
            assertThat(sources.get(0).getScore()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("应尊重 limit 参数")
        void shouldRespectLimit() {
            DocumentChunk c1 = chunk(1L, 101L, "内容一AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKKKKLLLLMMMMNNNNOOOOPPPPQQQQRRRRSSSSTTTTUUUU");
            DocumentChunk c2 = chunk(2L, 102L, "内容二AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKKKKLLLLMMMMNNNNOOOOPPPPQQQQRRRRSSSSTTTTUUUU");
            DocumentChunk c3 = chunk(3L, 103L, "内容三AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKKKKLLLLMMMMNNNNOOOOPPPPQQQQRRRRSSSSTTTTUUUU");

            List<ChatResponse.SourceInfo> sources = RagPromptTemplate.buildSources(
                    List.of(
                            new VectorStore.Hit(1L, 0.9f),
                            new VectorStore.Hit(2L, 0.8f),
                            new VectorStore.Hit(3L, 0.7f)
                    ),
                    id -> id == 1L ? c1 : (id == 2L ? c2 : c3),
                    id -> doc(100L + id, "文档" + id),
                    2   // 限制 2 条
            );

            assertThat(sources).hasSize(2);
        }

        @Test
        @DisplayName("snippet 超长应截断加省略号")
        void shouldTruncateLongSnippet() {
            String longText = "A".repeat(200);
            DocumentChunk chunk = chunk(1L, 100L, longText);
            Document doc = doc(100L, "长文档");

            List<ChatResponse.SourceInfo> sources = RagPromptTemplate.buildSources(
                    List.of(new VectorStore.Hit(1L, 0.8f)),
                    id -> chunk,
                    id -> doc
            );

            assertThat(sources).hasSize(1);
            String snippet = sources.get(0).getSnippet();
            assertThat(snippet).endsWith("...");
            assertThat(snippet.length()).isLessThanOrEqualTo(103); // 100 + "..."
        }

        @Test
        @DisplayName("chunk 为 null 时应跳过")
        void shouldSkipNullChunkInSources() {
            List<ChatResponse.SourceInfo> sources = RagPromptTemplate.buildSources(
                    List.of(new VectorStore.Hit(1L, 0.9f)),
                    id -> null,
                    id -> doc(100L, "测试")
            );

            assertThat(sources).isEmpty();
        }

        @Test
        @DisplayName("空 hits 列表应返回空 sources")
        void shouldReturnEmptyForNoHits() {
            List<ChatResponse.SourceInfo> sources = RagPromptTemplate.buildSources(
                    Collections.emptyList(),
                    id -> null,
                    id -> null
            );

            assertThat(sources).isEmpty();
        }

        @Test
        @DisplayName("文档为 null 时应标记为'未知'")
        void shouldMarkUnknownWhenDocIsNull() {
            DocumentChunk chunk = chunk(1L, 100L, "测试内容AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKKKKLLLLMMMMNNNNOOOOPPPPQQQQRRRRSSSSTTTT");
            chunk.setPageStart(1);
            chunk.setPageEnd(1);

            List<ChatResponse.SourceInfo> sources = RagPromptTemplate.buildSources(
                    List.of(new VectorStore.Hit(1L, 0.8f)),
                    id -> chunk,
                    id -> null
            );

            assertThat(sources).hasSize(1);
            assertThat(sources.get(0).getTitle()).isEqualTo("未知");
            assertThat(sources.get(0).getPageStart()).isEqualTo(1);
        }

        @Test
        @DisplayName("应携带页码信息")
        void shouldCarryPageInfo() {
            DocumentChunk chunk = chunk(1L, 100L, "内容 AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKKKKLLLLMMMMNNNNOOOOPPPPQQQQRRRRSSSSTTTT");
            chunk.setPageStart(5);
            chunk.setPageEnd(7);
            Document doc = doc(100L, "PDF文档");
            doc.setFileType("PDF");

            List<ChatResponse.SourceInfo> sources = RagPromptTemplate.buildSources(
                    List.of(new VectorStore.Hit(1L, 0.85f)),
                    id -> chunk,
                    id -> doc
            );

            assertThat(sources.get(0).getPageStart()).isEqualTo(5);
            assertThat(sources.get(0).getPageEnd()).isEqualTo(7);
            assertThat(sources.get(0).getFileType()).isEqualTo("PDF");
        }

        private DocumentChunk chunk(Long id, Long docId, String text) {
            DocumentChunk c = new DocumentChunk();
            c.setId(id);
            c.setDocumentId(docId);
            c.setChunkText(text);
            return c;
        }

        private Document doc(Long id, String title) {
            Document d = new Document();
            d.setId(id);
            d.setTitle(title);
            return d;
        }
    }
}
