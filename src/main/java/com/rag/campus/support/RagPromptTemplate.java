package com.rag.campus.support;

import com.rag.campus.dto.ChatResponse;
import com.rag.campus.entity.Document;
import com.rag.campus.entity.DocumentChunk;

import java.util.List;
import java.util.Objects;
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
                5. 语气友好、耐心，服务于在校学生""";
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
                    .append("》，匹配度: ")
                    .append(String.format("%.2f", hit.getScore()))
                    .append("）---\n")
                    .append(chunk.getChunkText())
                    .append("\n\n");
        }

        return contextBuilder.toString() +
               "用户问题：" + question + "\n\n" +
               "请根据以上参考资料回答。";
    }

    /**
     * 从检索结果构建 SourceInfo 列表
     */
    public static List<ChatResponse.SourceInfo> buildSources(
            List<VectorStore.Hit> hits,
            java.util.function.Function<Long, DocumentChunk> chunkResolver,
            java.util.function.Function<Long, Document> documentResolver) {

        return hits.stream().map(hit -> {
            DocumentChunk chunk = chunkResolver.apply(hit.getChunkId());
            if (chunk == null) return null;

            Document doc = documentResolver.apply(chunk.getDocumentId());

            String snippet = chunk.getChunkText();
            if (snippet.length() > 100) {
                snippet = snippet.substring(0, 100) + "...";
            }

            return ChatResponse.SourceInfo.builder()
                    .title(doc != null ? doc.getTitle() : "未知")
                    .chunkIndex(chunk.getChunkIndex())
                    .score((double) hit.getScore())
                    .snippet(snippet)
                    .build();
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }
}
