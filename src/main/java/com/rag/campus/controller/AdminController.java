package com.rag.campus.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.campus.common.Result;
import com.rag.campus.entity.User;
import com.rag.campus.mapper.UserMapper;
import com.rag.campus.service.UserService;
import com.rag.campus.support.VectorStore;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理接口 — 系统状态、用户管理、向量索引等
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final VectorStore vectorStore;
    private final UserService userService;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    // ==================== 权限校验 ====================

    private Result checkAdmin() {
        User user = userService.getCurrentUser();
        if (user == null || !"ADMIN".equals(user.getRole())) {
            return Result.fail("仅管理员可操作");
        }
        return null;
    }

    // ==================== 向量统计 ====================

    @GetMapping("/vector-stats")
    public Result vectorStats() {
        Result check = checkAdmin();
        if (check != null) return check;

        Map<String, Object> stats = new HashMap<>();
        stats.put("storeType", vectorStore.getClass().getSimpleName());
        stats.put("totalVectors", vectorStore.size());
        return Result.ok(stats);
    }

    // ==================== 用户管理 ====================

    /** 分页查询用户列表 */
    @GetMapping("/users")
    public Result listUsers(@RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "10") int size) {
        Result check = checkAdmin();
        if (check != null) return check;

        Page<User> pageResult = userMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<User>().orderByDesc(User::getCreateTime));

        Map<String, Object> data = new HashMap<>();
        data.put("records", pageResult.getRecords().stream().map(u -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", u.getId());
            item.put("username", u.getUsername());
            item.put("role", u.getRole());
            item.put("status", u.getStatus());
            item.put("createTime", u.getCreateTime() != null ? u.getCreateTime().toString() : null);
            return item;
        }).toList());
        data.put("total", pageResult.getTotal());
        data.put("current", pageResult.getCurrent());
        data.put("size", pageResult.getSize());
        return Result.ok(data);
    }

    /** 创建用户 */
    @PostMapping("/users")
    public Result createUser(@RequestBody Map<String, String> body) {
        Result check = checkAdmin();
        if (check != null) return check;

        String username = body.get("username");
        String password = body.get("password");
        String role = body.getOrDefault("role", "USER");
        String status = body.getOrDefault("status", "ACTIVE");

        if (StrUtil.isBlank(username)) return Result.fail("用户名不能为空");
        if (StrUtil.isBlank(password) || password.length() < 6) return Result.fail("密码长度不能少于6位");

        User exist = userService.findByUsername(username.trim());
        if (exist != null) return Result.fail("用户名已存在");

        User user = new User();
        user.setUsername(username.trim());
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(role);
        user.setStatus(status);
        userMapper.insert(user);

        return Result.ok("创建成功");
    }

    /** 编辑用户信息 */
    @PutMapping("/users/{id}")
    public Result updateUser(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Result check = checkAdmin();
        if (check != null) return check;

        User user = userMapper.selectById(id);
        if (user == null) return Result.fail("用户不存在");

        String username = body.get("username");
        String role = body.get("role");
        String status = body.get("status");

        if (StrUtil.isNotBlank(username)) {
            if (!username.trim().equals(user.getUsername())) {
                User exist = userService.findByUsername(username.trim());
                if (exist != null && !exist.getId().equals(id)) return Result.fail("用户名已存在");
            }
            user.setUsername(username.trim());
        }
        if (StrUtil.isNotBlank(role)) user.setRole(role);
        if (StrUtil.isNotBlank(status)) user.setStatus(status);
        userMapper.updateById(user);

        return Result.ok("更新成功");
    }

    /** 重置密码 */
    @PutMapping("/users/{id}/password")
    public Result resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Result check = checkAdmin();
        if (check != null) return check;

        User user = userMapper.selectById(id);
        if (user == null) return Result.fail("用户不存在");

        String password = body.get("password");
        if (StrUtil.isBlank(password) || password.length() < 6) return Result.fail("密码长度不能少于6位");

        user.setPassword(passwordEncoder.encode(password));
        userMapper.updateById(user);

        return Result.ok("密码重置成功");
    }

    /** 删除用户 */
    @DeleteMapping("/users/{id}")
    public Result deleteUser(@PathVariable Long id) {
        Result check = checkAdmin();
        if (check != null) return check;

        User user = userMapper.selectById(id);
        if (user == null) return Result.fail("用户不存在");
        if ("ADMIN".equals(user.getRole())) return Result.fail("不能删除管理员账号");

        userMapper.deleteById(id);
        return Result.ok("删除成功");
    }
}
