package com.rag.campus.support.impl;

import com.rag.campus.support.DocumentConverter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 纯文本提取器 — 适用 .txt / .md / .markdown 等文本格式
 */
@Component
public class PlainTextConverter implements DocumentConverter {

    private static final Set<String> EXTENSIONS = Set.of(".txt", ".md", ".markdown");

    @Override
    public String convert(byte[] fileBytes, String filename) throws IOException {
        return new String(fileBytes, StandardCharsets.UTF_8);
    }

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }
}
