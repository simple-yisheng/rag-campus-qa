package com.rag.campus;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring Boot 集成测试 — 验证应用上下文能正常加载
 * <p>
 * 运行前需要先启动 Docker 中间件：
 * <pre>
 *   docker compose up -d    # MySQL/Redis/RabbitMQ/MinIO
 *   mvn test -Dtest=RagIntegrationTest
 * </pre>
 * <p>
 * 日常开发直接跑单元测试即可（无需 Docker）：
 * <pre>
 *   mvn test -Dtest='!RagIntegrationTest'
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("RagIntegrationTest (需 Docker)")
@Disabled("需要 Docker 中间件支持：docker compose up -d 启动 MySQL/Redis/RabbitMQ/MinIO 后方可运行")
class RagApplicationTests {

    @Test
    @DisplayName("Spring 容器应成功启动")
    void contextLoads() {
        // 如果容器启动失败，此测试直接报错
    }
}
