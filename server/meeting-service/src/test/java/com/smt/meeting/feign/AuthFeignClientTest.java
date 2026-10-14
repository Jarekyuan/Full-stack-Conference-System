package com.smt.meeting.feign;

import com.smt.common.result.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Feign 调用双态验证（依赖 Nacos + auth-service 已启动）：
 * - auth 在线时执行 batchUsersShouldReturnUsersWhenAuthUp
 * - 停掉 auth 后单独执行 batchUsersShouldDegradeWhenAuthDown（-Dtest=AuthFeignClientTest#batchUsersShouldDegradeWhenAuthDown）
 */
@SpringBootTest
class AuthFeignClientTest {

    @Autowired
    private AuthFeignClient authFeignClient;

    @Test
    void batchUsersShouldReturnUsersWhenAuthUp() {
        Result<List<UserBriefVO>> result = authFeignClient.batchUsers(new UserBatchDTO(List.of(1L, 4L, 5L)));

        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals(3, result.getData().size());
        assertEquals("张三", result.getData().stream()
                .filter(user -> user.id() == 4L).findFirst().orElseThrow().realName());
    }

    @Test
    void batchUsersShouldDegradeWhenAuthDown() {
        Result<List<UserBriefVO>> result = authFeignClient.batchUsers(new UserBatchDTO(List.of(1L, 4L, 5L)));

        // 降级：返回 success + 空列表（不抛异常，调用方按"查不到姓名"处理）
        assertEquals(0, result.getCode());
        assertEquals(List.of(), result.getData());
    }
}
