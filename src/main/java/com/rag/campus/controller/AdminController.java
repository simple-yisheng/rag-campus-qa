package com.rag.campus.controller;

import com.rag.campus.common.Result;
import com.rag.campus.entity.User;
import com.rag.campus.service.UserService;
import com.rag.campus.support.VectorStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理接口 — 系统状态、向量索引等
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final VectorStore vectorStore;
    private final UserService userService;

    /**
     * 向量存储统计信息
     * <p>
     * GET /api/admin/vector-stats
     * <p>
     * 返回向量存储类型、总向量数、Collection 信息。
     * 仅管理员可调用（可在 SecurityConfig 中配置）。
     */
    @GetMapping("/vector-stats")
    public Result vectorStats() {
        User user = userService.getCurrentUser();
        if (user == null || !"ADMIN".equals(user.getRole())) {
            return Result.fail("仅管理员可查看");
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("storeType", vectorStore.getClass().getSimpleName());
        stats.put("totalVectors", vectorStore.size());
        return Result.ok(stats);
    }
}
