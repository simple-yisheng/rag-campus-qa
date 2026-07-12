package com.rag.campus.controller;

import com.rag.campus.common.Result;
import com.rag.campus.dto.LoginRequest;
import com.rag.campus.dto.LoginResponse;
import com.rag.campus.dto.RegisterRequest;
import com.rag.campus.entity.User;
import com.rag.campus.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthController 接口层测试
 * <p>
 * 使用 standalone MockMvc（不加载 Spring 容器），验证：
 * 1. 参数校验 → 返回正确错误码
 * 2. 正常流程 → 返回正确数据
 * 3. 异常 → 被异常处理器捕获并转换为 Result.fail
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController")
class AuthControllerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private AuthController authController;

    private MockMvc mockMvc;

    /**
     * 测试专用异常处理器（使用 ResponseEntity 替代 @ResponseStatus，
     * 确保 standalone MockMvc 模式下的响应体正确序列化）。
     */
    @RestControllerAdvice
    static class TestExceptionHandler {

        @ExceptionHandler(IllegalArgumentException.class)
        ResponseEntity<Result> handleIllegalArgument(IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(e.getMessage()));
        }

        @ExceptionHandler(Exception.class)
        ResponseEntity<Result> handleUnknown(Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Result.fail("服务器内部错误"));
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(authController)
                .setControllerAdvice(new TestExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    // ==================== POST /api/auth/register ====================

    @Nested
    @DisplayName("POST /api/auth/register")
    class Register {

        @Test
        @DisplayName("正常注册应返回 200 + token")
        void shouldReturnTokenOnSuccess() throws Exception {
            LoginResponse mockResponse = LoginResponse.builder()
                    .token("jwt-token-xyz")
                    .username("newuser")
                    .role("USER")
                    .build();
            when(userService.register(any(RegisterRequest.class))).thenReturn(mockResponse);

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"newuser","password":"123456","confirmPassword":"123456"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.token").value("jwt-token-xyz"))
                    .andExpect(jsonPath("$.data.username").value("newuser"))
                    .andExpect(jsonPath("$.data.role").value("USER"));
        }

        @Test
        @DisplayName("用户名已存在应返回 400")
        void shouldReturn400ForDuplicateUsername() throws Exception {
            when(userService.register(any(RegisterRequest.class)))
                    .thenThrow(new IllegalArgumentException("用户名已存在"));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"exist","password":"123456","confirmPassword":"123456"}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorMsg").value("用户名已存在"));
        }

        @Test
        @DisplayName("密码过短应返回 400")
        void shouldReturn400ForShortPassword() throws Exception {
            when(userService.register(any(RegisterRequest.class)))
                    .thenThrow(new IllegalArgumentException("密码长度不能少于6位"));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"user","password":"123","confirmPassword":"123"}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorMsg").value("密码长度不能少于6位"));
        }

        @Test
        @DisplayName("Server 内部异常应返回 500")
        void shouldReturn500ForInternalError() throws Exception {
            when(userService.register(any(RegisterRequest.class)))
                    .thenThrow(new RuntimeException("数据库连接失败"));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"user","password":"123456","confirmPassword":"123456"}"""))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorMsg").value("服务器内部错误"));
        }
    }

    // ==================== POST /api/auth/login ====================

    @Nested
    @DisplayName("POST /api/auth/login")
    class Login {

        @Test
        @DisplayName("正常登录应返回 200 + token")
        void shouldReturnTokenOnSuccess() throws Exception {
            LoginResponse mockResponse = LoginResponse.builder()
                    .token("jwt-token-abc")
                    .username("testuser")
                    .role("USER")
                    .build();
            when(userService.login(any(LoginRequest.class))).thenReturn(mockResponse);

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"testuser","password":"123456"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.token").value("jwt-token-abc"));
        }

        @Test
        @DisplayName("密码错误应返回 400")
        void shouldReturn400ForWrongPassword() throws Exception {
            when(userService.login(any(LoginRequest.class)))
                    .thenThrow(new IllegalArgumentException("用户名或密码错误"));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"testuser","password":"wrong"}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorMsg").value("用户名或密码错误"));
        }

        @Test
        @DisplayName("用户名为空应返回 400")
        void shouldReturn400ForEmptyUsername() throws Exception {
            when(userService.login(any(LoginRequest.class)))
                    .thenThrow(new IllegalArgumentException("用户名不能为空"));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"","password":"123456"}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorMsg").value("用户名不能为空"));
        }
    }

    // ==================== GET /api/auth/me ====================

    @Nested
    @DisplayName("GET /api/auth/me")
    class Me {

        @Test
        @DisplayName("未登录应返回 fail")
        void shouldReturnFailWhenNotLoggedIn() throws Exception {
            when(userService.getCurrentUser()).thenReturn(null);

            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorMsg").value("未登录"));
        }

        @Test
        @DisplayName("已登录应返回用户信息（不含密码）")
        void shouldReturnUserInfoWithoutPassword() throws Exception {
            User user = new User();
            user.setId(1L);
            user.setUsername("testuser");
            user.setPassword("encrypted-password");
            user.setRole("USER");
            user.setStatus("ACTIVE");
            when(userService.getCurrentUser()).thenReturn(user);

            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.username").value("testuser"))
                    .andExpect(jsonPath("$.data.role").value("USER"))
                    .andExpect(jsonPath("$.data.password").doesNotExist());
        }
    }
}
