package com.rag.campus;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 校园智答 — 基于RAG架构的校园知识库问答系统
 * <p>
 * 核心功能：
 * 1. 文档上传 + 自动分块 + 向量化（异步MQ处理）
 * 2. 语义检索（余弦相似度 Top-K）
 * 3. RAG问答（检索增强生成）
 * 4. 多轮对话（Redis缓存历史）
 */
@SpringBootApplication
@MapperScan("com.rag.campus.mapper")
public class RagCampusApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagCampusApplication.class, args);
        System.out.println("========================================");
        System.out.println("  校园智答 RAG 系统启动成功！");
        System.out.println("  文档上传: POST /api/documents/upload");
        System.out.println("  知识问答: POST /api/chat/ask");
        System.out.println("========================================");
    }
}
