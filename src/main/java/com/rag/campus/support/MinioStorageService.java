package com.rag.campus.support;

import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 对象存储服务 — 原始文件的上传 / 下载 / 删除
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioStorageService {

    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    /**
     * 确保 Bucket 存在（启动时调用一次即可）
     */
    public void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO Bucket 创建成功: {}", bucket);
            }
        } catch (Exception e) {
            log.error("MinIO Bucket 初始化失败: {}", bucket, e);
            throw new RuntimeException("MinIO Bucket 初始化失败", e);
        }
    }

    /**
     * 上传文件到 MinIO
     *
     * @param documentId    文档ID
     * @param originalName  原始文件名（含扩展名，如 "奖学金办法.docx"）
     * @param fileBytes     文件字节
     * @param contentType   MIME 类型
     * @return MinIO 对象 key
     */
    public String upload(Long documentId, String originalName, byte[] fileBytes, String contentType) {
        String key = buildKey(documentId, originalName);

        try (ByteArrayInputStream bis = new ByteArrayInputStream(fileBytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .stream(bis, fileBytes.length, -1)
                            .contentType(contentType)
                            .build());
            log.info("文件已上传到MinIO: bucket={}, key={}, size={}bytes", bucket, key, fileBytes.length);
            return key;
        } catch (Exception e) {
            log.error("MinIO上传失败: key={}", key, e);
            throw new RuntimeException("文件存储失败", e);
        }
    }

    /**
     * 下载文件流
     */
    public InputStream download(String key) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .build());
        } catch (Exception e) {
            log.error("MinIO下载失败: key={}", key, e);
            throw new RuntimeException("文件获取失败", e);
        }
    }

    /**
     * 获取文件元信息（大小、类型等）
     */
    public StatObjectResponse stat(String key) {
        try {
            return minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .build());
        } catch (Exception e) {
            log.error("MinIO元信息查询失败: key={}", key, e);
            throw new RuntimeException("文件信息获取失败", e);
        }
    }

    /**
     * 删除文件
     */
    public void delete(String key) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .build());
            log.info("MinIO文件已删除: key={}", key);
        } catch (Exception e) {
            log.error("MinIO删除失败: key={}", key, e);
        }
    }

    // ==================== 私有方法 ====================

    private String buildKey(Long documentId, String originalName) {
        return "documents/" + documentId + "/" + originalName;
    }
}
