package com.rag.campus.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Result 统一响应包装类单元测试
 */
@DisplayName("Result")
class ResultTest {

    @Test
    @DisplayName("ok() 应返回 success=true, data=null")
    void okWithoutData() {
        Result result = Result.ok();

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getErrorMsg()).isNull();
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("ok(data) 应携带数据")
    void okWithData() {
        String data = "hello";
        Result result = Result.ok(data);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("hello");
    }

    @Test
    @DisplayName("ok(null) 应 success=true 且 data=null")
    void okWithNullData() {
        Result result = Result.ok(null);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("fail(msg) 应返回 success=false 并携带错误信息")
    void failWithMessage() {
        Result result = Result.fail("用户名不能为空");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("用户名不能为空");
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("ok 和 fail 应互斥 — 不能同时 success=true 且有 errorMsg")
    void successAndErrorAreMutuallyExclusive() {
        Result ok = Result.ok("data");
        assertThat(ok.getSuccess()).isTrue();
        assertThat(ok.getErrorMsg()).isNull();

        Result fail = Result.fail("错误");
        assertThat(fail.getSuccess()).isFalse();
        assertThat(fail.getData()).isNull();
    }
}
