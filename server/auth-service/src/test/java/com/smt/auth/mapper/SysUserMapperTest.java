package com.smt.auth.mapper;

import com.smt.auth.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 手写 SQL 联调测试（依赖本地 MySQL/Redis 已启动；禁用 Nacos 注册避免测试期产生临时实例）
 */
@SpringBootTest(properties = "spring.cloud.nacos.discovery.enabled=false")
class SysUserMapperTest {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Test
    void selectByUsernameShouldReturnSeedUser() {
        SysUser user = sysUserMapper.selectByUsername("user1");
        assertNotNull(user);
        assertEquals(4L, user.getId());
        assertEquals("张三", user.getRealName());
        assertEquals("USER", user.getRole());
        assertEquals(1, user.getStatus());
        assertNotNull(user.getCreatedAt());
    }

    @Test
    void selectByUsernameShouldReturnNullWhenNotExists() {
        assertNull(sysUserMapper.selectByUsername("nobody"));
    }
}
