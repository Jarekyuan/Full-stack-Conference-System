package com.smt.meeting.feign;

import com.smt.common.result.ErrorCode;
import com.smt.common.result.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

        // 降级：不抛异常（不阻断主流程），但必须是**非 0 code**——
        // 旧实现回 success + 空列表，调用方分不清"auth 挂了"与"这批 id 查无此人"（评审 P1-16）
        assertEquals(ErrorCode.SYSTEM_BUSY.getCode(), result.getCode());
        assertNull(result.getData());
    }
}
