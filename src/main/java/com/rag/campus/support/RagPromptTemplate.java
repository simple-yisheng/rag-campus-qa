package com.rag.campus.support;

import com.rag.campus.dto.ChatResponse;
import com.rag.campus.entity.Document;
import com.rag.campus.entity.DocumentChunk;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG Prompt 模板
 * <p>
 * 面试要点：
 * 1. Prompt Engineering 是RAG系统质量的关键
 * 2. "仅根据参考资料回答" → 防止模型编造（大模型幻觉）
 * 3. "标注引用来源" → 增强可信度
 * 4. 可扩展：不同分类的文档可用不同的system prompt
 */
public class RagPromptTemplate {

    /**
     * 构建RAG的System Prompt
     */
    public static String buildSystemPrompt() {
        return """
                你是一个校园知识库问答助手"校园智答"，专门为在校学生解答关于学校规章制度、\
                评奖评优、学业政策、生活指南等方面的问题。

                请严格遵循以下规则回答用户问题：
                1. 仅根据【参考资料】中的内容进行回答，不得使用你自己的知识
                2. 如果参考资料中没有相关信息，请如实告知用户"该问题在知识库中暂无收录，建议咨询辅导员或查看学校官网"
                3. 回答要简洁清晰、条理分明，使用序号或分段提高可读性
                4. 在回答末尾标注引用的资料标题
                5. 如果参考资料包含表格、名录、清单、项目列表，请优先使用 Markdown 表格输出，保留原有列含义
                6. 列举多个条目时，每个条目必须独立成行，禁止把大量条目用短横线或顿号连续拼接成一整段
                7. 表格、名录、清单类内容默认只展示最相关或最靠前的 8 项，不要完整展开长表
                8. 语气友好、耐心，服务于在校学生""";
    }

    /**
     * 构建包含检索上下文的User Prompt
     *
     * @param question 用户原始问题
     * @param hits     检索到的相关chunk列表
     * @return 组装好的prompt
     */
    public static String buildUserPrompt(String question,
                                         List<VectorStore.Hit> hits,
                                         java.util.function.Function<Long, DocumentChunk> chunkResolver,
                                         java.util.function.Function<Long, Document> documentResolver) {

        StringBuilder contextBuilder = new StringBuilder();

        for (int i = 0; i < hits.size(); i++) {
            VectorStore.Hit hit = hits.get(i);
            DocumentChunk chunk = chunkResolver.apply(hit.getChunkId());
            if (chunk == null) continue;

            Document doc = documentResolver.apply(chunk.getDocumentId());
            String title = doc != null ? doc.getTitle() : "未知文档";

            contextBuilder.append("--- 资料片段 ")
                    .append(i + 1)
                    .append("（来源：《")
                    .append(title)
                    .append("》，相关度: ")
                    .append(String.format("%.2f", Math.min(1.0, hit.getScore())))
                    .append("）---\n")
                    .append(chunk.getChunkText())
                    .append("\n\n");
        }

        return contextBuilder.toString() +
               "用户问题：" + question + "\n\n" +
               """
               请根据以上参考资料回答。
               输出格式要求：
               1. 如果用户询问"有哪些/名单/名录/清单/项目"，优先整理为 Markdown 表格。
               2. 如果资料中出现"竞赛名称/竞赛级别"等列，请输出为表格，列名建议为：序号、竞赛名称、竞赛级别。
               3. 不要把多个竞赛、项目或条目压缩在同一行；一项一行。
               4. 表格、名录、清单类内容默认只输出最相关或最靠前的 8 项。
               5. 如果条目超过 8 项，在表格后说明"以上仅列出部分条目，完整名单请点击参考资料查看原文"。""";
    }

    /**
     * 构建查询改写的 User Prompt
     * <p>
     * 将多轮对话中的追问（如"那第三章呢""在第几页"）改写为独立完整的查询，
     * 使其脱离上下文后仍能被正确理解和检索。
     *
     * @param history         最近N轮对话的原始Q&A（不含chunk）
     * @param currentQuestion 用户当前追问
     * @return 查询改写 prompt
     */
    public static String buildRewritePrompt(List<Map<String, String>> history,
                                            String currentQuestion) {
        StringBuilder historyBuilder = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            Map<String, String> msg = history.get(i);
            String role = "user".equals(msg.get("role")) ? "用户" : "助手";
            historyBuilder.append(role).append("：").append(msg.get("content")).append("\n");
        }

        return historyBuilder.toString()
               + "用户：" + currentQuestion + "\n\n"
               + "请将上面用户最后一句追问改写为一个独立、完整的问题，使其脱离上下文也能被正确理解和检索。"
               + "改写时需要补全省略的主语、指代（如'它''这个''那'）、隐含的文档/章节引用等。"
               + "如果用户的问题本身已经完整独立，直接返回原句。"
               + "\n\n只输出改写后的问题，不要加任何解释或前缀。";
    }

    /**
     * 从检索结果构建 SourceInfo 列表
     */
    public static List<ChatResponse.SourceInfo> buildSources(
            List<VectorStore.Hit> hits,
            java.util.function.Function<Long, DocumentChunk> chunkResolver,
            java.util.function.Function<Long, Document> documentResolver) {
        return buildSources(hits, chunkResolver, documentResolver, Integer.MAX_VALUE);
    }

    /**
     * 从检索结果构建 SourceInfo 列表，并限制展示数量。
     */
    public static List<ChatResponse.SourceInfo> buildSources(
            List<VectorStore.Hit> hits,
            java.util.function.Function<Long, DocumentChunk> chunkResolver,
            java.util.function.Function<Long, Document> documentResolver,
            int limit) {

        // 按 documentId 去重，每篇文档只保留最高分的 chunk
        Map<Long, ChatResponse.SourceInfo> bestPerDoc = new LinkedHashMap<>();

        for (VectorStore.Hit hit : hits) {
            DocumentChunk chunk = chunkResolver.apply(hit.getChunkId());
            if (chunk == null) continue;

            Document doc = documentResolver.apply(chunk.getDocumentId());
            Long docId = chunk.getDocumentId();

            // 同文档只保留最高分
            if (!bestPerDoc.containsKey(docId) || hit.getScore() > bestPerDoc.get(docId).getScore()) {
                String snippet = chunk.getChunkText();
                if (snippet.length() > 100) {
                    snippet = snippet.substring(0, 100) + "...";
                }
                bestPerDoc.put(docId, ChatResponse.SourceInfo.builder()
                        .documentId(docId)
                        .title(doc != null ? doc.getTitle() : "未知")
                        .fileType(doc != null ? doc.getFileType() : null)
                        .chunkIndex(chunk.getChunkIndex())
                        .pageStart(chunk.getPageStart())
                        .pageEnd(chunk.getPageEnd())
                        .score(Math.min(1.0, (double) hit.getScore()))
                        .snippet(snippet)
                        .build());
            }
        }

        return bestPerDoc.values().stream()
                .limit(Math.max(0, limit))
                .collect(Collectors.toList());
    }
}
