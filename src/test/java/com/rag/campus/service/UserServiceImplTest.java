package com.rag.campus.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.campus.dto.LoginRequest;
import com.rag.campus.dto.LoginResponse;
import com.rag.campus.dto.RegisterRequest;
import com.rag.campus.entity.User;
import com.rag.campus.mapper.UserMapper;
import com.rag.campus.security.JwtUtil;
import com.rag.campus.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UserServiceImpl 单元测试
 * <p>
 * 使用 Mockito 隔离所有外部依赖（Mapper、PasswordEncoder、JwtUtil）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceImpl")
class UserServiceImplTest {

    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;

    @InjectMocks
    private UserServiceImpl userService;

    // ==================== register ====================

    @Nested
    @DisplayName("register")
    class Register {

        private RegisterRequest validRequest;

        @BeforeEach
        void setUp() {
            validRequest = new RegisterRequest();
            validRequest.setUsername("newuser");
            validRequest.setPassword("123456");
            validRequest.setConfirmPassword("123456");
        }

        @Test
        @DisplayName("正常注册应返回 token 和用户信息")
        void shouldRegisterSuccessfully() {
            when(userMapper.selectOne(any())).thenReturn(null);         // 用户名不存在
            when(passwordEncoder.encode("123456")).thenReturn("$2a$encoded");
            when(userMapper.insert(any())).thenReturn(1);
            when(jwtUtil.generateToken("newuser", "USER"))
                    .thenReturn("jwt-token-xyz");

            LoginResponse response = userService.register(validRequest);

            assertThat(response.getToken()).isEqualTo("jwt-token-xyz");
            assertThat(response.getUsername()).isEqualTo("newuser");
            assertThat(response.getRole()).isEqualTo("USER");

            // 验证 insert 的 User 对象
            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userMapper).insert(userCaptor.capture());
            User saved = userCaptor.getValue();
            assertThat(saved.getUsername()).isEqualTo("newuser");
            assertThat(saved.getPassword()).isEqualTo("$2a$encoded");
            assertThat(saved.getRole()).isEqualTo("USER");
            assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("用户名为空应抛异常")
        void shouldThrowForBlankUsername() {
            validRequest.setUsername("");

            assertThatThrownBy(() -> userService.register(validRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("用户名不能为空");

            verify(userMapper, never()).insert(any());
        }

        @Test
        @DisplayName("用户名 null 应抛异常")
        void shouldThrowForNullUsername() {
            validRequest.setUsername(null);

            assertThatThrownBy(() -> userService.register(validRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("用户名不能为空");
        }

        @Test
        @DisplayName("密码少于 6 位应抛异常")
        void shouldThrowForShortPassword() {
            validRequest.setPassword("12345");
            validRequest.setConfirmPassword("12345");

            assertThatThrownBy(() -> userService.register(validRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("密码长度不能少于6位");
        }

        @Test
        @DisplayName("两次密码不一致应抛异常")
        void shouldThrowForMismatchedPasswords() {
            validRequest.setConfirmPassword("different");

            assertThatThrownBy(() -> userService.register(validRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("两次输入的密码不一致");
        }

        @Test
        @DisplayName("用户名已存在应抛异常")
        void shouldThrowForDuplicateUsername() {
            when(userMapper.selectOne(any())).thenReturn(new User());

            assertThatThrownBy(() -> userService.register(validRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("用户名已存在");

            verify(userMapper, never()).insert(any());
        }

        @Test
        @DisplayName("用户名应 trim 后保存")
        void shouldTrimUsername() {
            validRequest.setUsername("  user  ");

            when(userMapper.selectOne(any())).thenReturn(null);
            when(passwordEncoder.encode(any())).thenReturn("encoded");
            when(userMapper.insert(any())).thenReturn(1);
            when(jwtUtil.generateToken(eq("user"), any())).thenReturn("token");

            LoginResponse response = userService.register(validRequest);

            assertThat(response.getUsername()).isEqualTo("user");
        }
    }

    // ==================== login ====================

    @Nested
    @DisplayName("login")
    class Login {

        private LoginRequest validRequest;
        private User dbUser;

        @BeforeEach
        void setUp() {
            validRequest = new LoginRequest();
            validRequest.setUsername("testuser");
            validRequest.setPassword("123456");

            dbUser = new User();
            dbUser.setId(1L);
            dbUser.setUsername("testuser");
            dbUser.setPassword("$2a$encoded");
            dbUser.setRole("USER");
            dbUser.setStatus("ACTIVE");
        }

        @Test
        @DisplayName("正常登录应返回 token")
        void shouldLoginSuccessfully() {
            when(userMapper.selectOne(any())).thenReturn(dbUser);
            when(passwordEncoder.matches("123456", "$2a$encoded")).thenReturn(true);
            when(jwtUtil.generateToken("testuser", "USER")).thenReturn("jwt-token");

            LoginResponse response = userService.login(validRequest);

            assertThat(response.getToken()).isEqualTo("jwt-token");
            assertThat(response.getUsername()).isEqualTo("testuser");
            assertThat(response.getRole()).isEqualTo("USER");
        }

        @Test
        @DisplayName("用户名不存在应抛异常")
        void shouldThrowForNonExistentUser() {
            when(userMapper.selectOne(any())).thenReturn(null);

            assertThatThrownBy(() -> userService.login(validRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("用户名或密码错误");
        }

        @Test
        @DisplayName("密码错误应抛异常")
        void shouldThrowForWrongPassword() {
            when(userMapper.selectOne(any())).thenReturn(dbUser);
            when(passwordEncoder.matches("123456", "$2a$encoded")).thenReturn(false);

            assertThatThrownBy(() -> userService.login(validRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("用户名或密码错误");
        }

        @Test
        @DisplayName("用户名为空应抛异常")
        void shouldThrowForEmptyUsername() {
            validRequest.setUsername("");

            assertThatThrownBy(() -> userService.login(validRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("用户名不能为空");
        }

        @Test
        @DisplayName("密码为空应抛异常")
        void shouldThrowForEmptyPassword() {
            validRequest.setPassword("");

            assertThatThrownBy(() -> userService.login(validRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("密码不能为空");
        }

        @Test
        @DisplayName("被禁用的账号应抛异常")
        void shouldThrowForDisabledAccount() {
            dbUser.setStatus("DISABLED");
            when(userMapper.selectOne(any())).thenReturn(dbUser);

            assertThatThrownBy(() -> userService.login(validRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("账号已被禁用");

            // 不应该检查密码
            verify(passwordEncoder, never()).matches(any(), any());
        }
    }

    // ==================== findByUsername ====================

    @Nested
    @DisplayName("findByUsername")
    class FindByUsername {

        @Test
        @DisplayName("存在的用户应返回 User 对象")
        void shouldReturnUserIfExists() {
            User user = new User();
            user.setUsername("testuser");
            when(userMapper.selectOne(any())).thenReturn(user);

            User result = userService.findByUsername("testuser");
            assertThat(result).isNotNull();
            assertThat(result.getUsername()).isEqualTo("testuser");
        }

        @Test
        @DisplayName("不存在的用户应返回 null")
        void shouldReturnNullIfNotExists() {
            when(userMapper.selectOne(any())).thenReturn(null);

            User result = userService.findByUsername("nonexistent");
            assertThat(result).isNull();
        }
    }
}
