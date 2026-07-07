package com.rag.campus.support;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MinIO 初始化 — 确保 Bucket 存在
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioInitializer {

    private final MinioStorageService storageService;

    @PostConstruct
    public void init() {
        storageService.ensureBucket();
    }
}
