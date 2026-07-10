package com.rag.campus.config;

import com.rag.campus.security.JwtAuthFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置 — JWT 无状态认证
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 禁用 CSRF（API 无状态，不需要）
                .csrf(AbstractHttpConfigurer::disable)
                // 无状态 session
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 路由权限
                .authorizeHttpRequests(auth -> auth
                        // SSE 使用 Servlet 异步派发；响应已开始后不能再触发二次鉴权/错误页鉴权
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        // 认证接口公开
                        .requestMatchers("/api/auth/**").permitAll()
                        // 静态资源公开（SPA 前端）
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                        // 文件下载/预览公开（方便嵌入 iframe 等场景，仍可通过 ?download 强制下载）
                        .requestMatchers(HttpMethod.GET, "/api/documents/*/file", "/api/documents/*/preview").permitAll()
                        // 其余 API 需要认证
                        .requestMatchers("/api/**").authenticated()
                        // 其他所有请求放行
                        .anyRequest().permitAll()
                )
                // JWT 过滤器插在 UsernamePasswordAuthenticationFilter 之前
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
