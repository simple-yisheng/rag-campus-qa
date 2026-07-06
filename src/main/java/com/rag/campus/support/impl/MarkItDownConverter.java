package com.rag.campus.support.impl;

import com.rag.campus.support.DocumentConverter;

import java.io.IOException;
import java.util.Set;

/**
 * MarkItDown 转换器（预留扩展点，当前未启用）
 * <p>
 * 思路：用 MarkItDown（微软开源）将 PDF/Word/PPT/Excel 统一转为 Markdown。
 * 转为 Markdown 后，后续分块全部走 # 标题切分策略，不再需要按文件格式区分处理。
 * <p>
 * 集成方式（二选一）：
 * <p>
 * 方案A — 子进程调用（Demo 最简单）：
 *   <pre>
 *     // 前提：pip install markitdown
 *     ProcessBuilder pb = new ProcessBuilder("markitdown", "--input", tempFile.getPath());
 *     Process p = pb.start();
 *     return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
 *   </pre>
 * <p>
 * 方案B — Docker 旁路服务（生产推荐）：
 *   单独部署一个 markitdown Flask 容器，Java 通过 RestTemplate 调 REST 接口
 * <p>
 * 启用步骤：
 *   1. 安装 Python + markitdown
 *   2. 给本类加 @Component + @ConditionalOnProperty(name="doc.converter.markitdown.enabled")
 *   3. 在 application.yaml 中设 doc.converter.markitdown.enabled=true
 *   4. DocumentServiceImpl 自动注入（无需改代码）
 *
 * @see DocumentConverter 接口定义
 * @see PdfBoxConverter 当前 PDF 方案
 */
// @Component
// @ConditionalOnProperty(name = "doc.converter.markitdown.enabled", havingValue = "true")
public class MarkItDownConverter implements DocumentConverter {

    private static final Set<String> EXTENSIONS = Set.of(".pdf", ".docx", ".pptx", ".xlsx", ".html");

    @Override
    public String convert(byte[] fileBytes, String filename) throws IOException {
        // TODO: 方案A — 子进程调用
        /*
        Path tempFile = Files.createTempFile("doc-", filename);
        try {
            Files.write(tempFile, fileBytes);
            ProcessBuilder pb = new ProcessBuilder(
                    "markitdown", tempFile.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("MarkItDown 转换失败: exitCode=" + exitCode);
            }
            return result;
        } finally {
            Files.deleteIfExists(tempFile);
        }
        */
        throw new UnsupportedOperationException("MarkItDown 转换器未启用，请先安装 markitdown 并启用配置");
    }

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }
}
