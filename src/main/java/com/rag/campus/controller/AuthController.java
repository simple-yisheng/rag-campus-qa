package com.rag.campus.controller;

import com.rag.campus.common.Result;
import com.rag.campus.dto.LoginRequest;
import com.rag.campus.dto.LoginResponse;
import com.rag.campus.dto.RegisterRequest;
import com.rag.campus.entity.User;
import com.rag.campus.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口 — 登录 / 注册 / 获取当前用户
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /**
     * 注册
     */
    @PostMapping("/register")
    public Result register(@RequestBody RegisterRequest request) {
        LoginResponse response = userService.register(request);
        return Result.ok(response);
    }

    /**
     * 登录
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginRequest request) {
        LoginResponse response = userService.login(request);
        return Result.ok(response);
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/me")
    public Result me() {
        User user = userService.getCurrentUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        // 不返回密码
        user.setPassword(null);
        return Result.ok(user);
    }
}
