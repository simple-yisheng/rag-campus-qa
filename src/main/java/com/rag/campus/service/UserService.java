package com.rag.campus.service;

import com.rag.campus.dto.LoginRequest;
import com.rag.campus.dto.RegisterRequest;
import com.rag.campus.dto.LoginResponse;
import com.rag.campus.entity.User;

/**
 * 用户服务接口
 */
public interface UserService {

    /** 注册新用户，返回登录令牌 */
    LoginResponse register(RegisterRequest request);

    /** 登录，返回 JWT 令牌 */
    LoginResponse login(LoginRequest request);

    /** 获取当前登录用户 */
    User getCurrentUser();

    /** 根据用户名查找用户 */
    User findByUsername(String username);
}
