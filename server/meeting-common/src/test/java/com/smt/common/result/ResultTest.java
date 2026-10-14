package com.smt.common.result;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultTest {

    @Test
    void successShouldCarryCodeZeroAndData() {
        Result<String> result = Result.success("hello");
        assertEquals(0, result.getCode());
        assertEquals("ok", result.getMsg());
        assertEquals("hello", result.getData());

        Result<Void> empty = Result.success();
        assertEquals(0, empty.getCode());
        assertNull(empty.getData());
    }

    @Test
    void errorShouldCarryErrorCodeAndOptionalData() {
        Result<Object> plain = Result.error(ErrorCode.TIME_CONFLICT);
        assertEquals(40001, plain.getCode());
        assertEquals("所选时段与已有会议冲突", plain.getMsg());
        assertNull(plain.getData());

        Result<String> withData = Result.error(ErrorCode.TIME_CONFLICT, "conflicts");
        assertEquals("conflicts", withData.getData());
    }

    @Test
    void pageResultShouldBuild() {
        PageResult<String> pageResult = PageResult.of(List.of("a", "b"), 2, 1, 10);
        assertEquals(2, pageResult.getList().size());
        assertEquals(2, pageResult.getTotal());
        assertEquals(1, pageResult.getPage());
        assertEquals(10, pageResult.getSize());
    }

    @Test
    void errorCodesShouldBeUnique() {
        Set<Integer> seen = new HashSet<>();
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertTrue(seen.add(errorCode.getCode()), "发现重复错误码：" + errorCode.getCode());
        }
    }
}
