package com.rag.campus.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtUtil 单元测试
 * <p>
 * 直接 new JwtUtil(secret, expiration) 构造，不依赖 Spring 容器。
 */
@DisplayName("JwtUtil")
class JwtUtilTest {

    // 32+ 字节的测试密钥（jjwt 0.12 要求至少 256 bits）
    private static final String SECRET = "test-jwt-secret-key-for-unit-testing-only!";
    private static final long EXPIRATION_MS = 3600_000L; // 1 小时

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, EXPIRATION_MS);
    }

    // ==================== generateToken ====================

    @Nested
    @DisplayName("generateToken")
    class GenerateToken {

        @Test
        @DisplayName("应生成非空 Token")
        void shouldGenerateNonEmptyToken() {
            String token = jwtUtil.generateToken("testuser", "USER");
            assertThat(token).isNotBlank();
        }

        @Test
        @DisplayName("应为 JWT 三段式格式")
        void shouldBeJwtFormat() {
            String token = jwtUtil.generateToken("testuser", "USER");

            String[] parts = token.split("\\.");
            assertThat(parts).hasSize(3);
        }

        @Test
        @DisplayName("Token 应包含角色信息")
        void shouldContainRole() {
            String token = jwtUtil.generateToken("admin", "ADMIN");

            String role = jwtUtil.extractRole(token);
            assertThat(role).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("相同用户输入生成的 Token 应包含一致的用户名和角色")
        void sameInputShouldProduceConsistentClaims() {
            String token1 = jwtUtil.generateToken("user", "USER");
            String token2 = jwtUtil.generateToken("user", "USER");

            // 同一毫秒内两次调用可能产生相同 token（时间戳精度限制），
            // 重要的是 claims 一致
            assertThat(jwtUtil.extractUsername(token1)).isEqualTo("user");
            assertThat(jwtUtil.extractRole(token1)).isEqualTo("USER");
            assertThat(jwtUtil.extractUsername(token2))
                    .isEqualTo(jwtUtil.extractUsername(token2));
        }
    }

    // ==================== extractUsername ====================

    @Nested
    @DisplayName("extractUsername")
    class ExtractUsername {

        @Test
        @DisplayName("应正确提取用户名")
        void shouldExtractUsername() {
            String token = jwtUtil.generateToken("zhangsan", "USER");
            assertThat(jwtUtil.extractUsername(token)).isEqualTo("zhangsan");
        }

        @Test
        @DisplayName("中文用户名应正常提取")
        void shouldExtractChineseUsername() {
            String token = jwtUtil.generateToken("张三", "USER");
            assertThat(jwtUtil.extractUsername(token)).isEqualTo("张三");
        }
    }

    // ==================== validateToken ====================

    @Nested
    @DisplayName("validateToken")
    class ValidateToken {

        @Test
        @DisplayName("有效 Token 应返回 true")
        void shouldReturnTrueForValidToken() {
            String token = jwtUtil.generateToken("user", "USER");
            assertThat(jwtUtil.validateToken(token)).isTrue();
        }

        @Test
        @DisplayName("篡改的 Token 应返回 false")
        void shouldReturnFalseForTamperedToken() {
            String token = jwtUtil.generateToken("user", "USER");
            String tampered = token.substring(0, token.length() - 5) + "xxxxx";
            assertThat(jwtUtil.validateToken(tampered)).isFalse();
        }

        @Test
        @DisplayName("空 Token 应返回 false")
        void shouldReturnFalseForEmptyToken() {
            assertThat(jwtUtil.validateToken("")).isFalse();
        }

        @Test
        @DisplayName("纯乱码应返回 false")
        void shouldReturnFalseForGarbage() {
            assertThat(jwtUtil.validateToken("not.a.valid.token")).isFalse();
        }
    }

    // ==================== 短密钥补齐 ====================

    @Nested
    @DisplayName("短密钥自动补齐")
    class ShortKeyPadding {

        @Test
        @DisplayName("短于 32 字节的密钥应自动补齐并正常工作")
        void shouldPadShortKey() {
            // "short" 只有 5 字节，远短于 32
            JwtUtil shortKeyUtil = new JwtUtil("short", 3600_000L);

            String token = shortKeyUtil.generateToken("user", "USER");
            assertThat(shortKeyUtil.validateToken(token)).isTrue();
            assertThat(shortKeyUtil.extractUsername(token)).isEqualTo("user");
        }

        @Test
        @DisplayName("正好 32 字节的密钥应正常工作")
        void shouldWorkWithExact32ByteKey() {
            // 恰好 32 个 ASCII 字符 = 32 字节
            String exact32 = "abcdefghijklmnopqrstuvwxyz123456";  // 32 chars
            JwtUtil exactKeyUtil = new JwtUtil(exact32, 3600_000L);

            String token = exactKeyUtil.generateToken("user", "USER");
            assertThat(exactKeyUtil.validateToken(token)).isTrue();
        }
    }

    // ==================== 过期 Token ====================

    @Nested
    @DisplayName("过期 Token")
    class ExpiredToken {

        @Test
        @DisplayName("过期 Token 应校验失败")
        void shouldRejectExpiredToken() {
            // 过期时间设为 1ms（几乎立即过期）
            JwtUtil expiredUtil = new JwtUtil(SECRET, 1L);
            String token = expiredUtil.generateToken("user", "USER");

            // 等待 token 过期
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            assertThat(expiredUtil.validateToken(token)).isFalse();
        }
    }
}
