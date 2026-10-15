package com.smt.auth.mapper;

import com.smt.auth.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 手写 SQL 联调测试（依赖本地 MySQL/Redis 已启动；禁用 Nacos 注册避免测试期产生临时实例）
 * v1.3：角色改经 sys_user_role 关联查询
 */
@SpringBootTest(properties = "spring.cloud.nacos.discovery.enabled=false")
class SysUserMapperTest {

    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Test
    void selectByUsernameShouldReturnSeedUser() {
        SysUser user = sysUserMapper.selectByUsername("user1");
        assertNotNull(user);
        assertEquals(4L, user.getId());
        assertEquals("张三", user.getRealName());
        assertEquals(2L, user.getDeptId(), "user1 应在研发部（dept_id=2）");
        assertEquals(1, user.getStatus());
        assertNotNull(user.getCreatedAt());
    }

    @Test
    void selectByUsernameShouldReturnNullWhenNotExists() {
        assertNull(sysUserMapper.selectByUsername("nobody"));
    }

    @Test
    void userRolesShouldComeFromUserRoleTable() {
        // 单角色：user1 → USER
        assertEquals(List.of("USER"), sysRoleMapper.selectCodesByUserId(4L));
        // 多角色：admin 兼 ADMIN + APPROVER（按 role_id 升序输出）
        assertEquals(List.of("APPROVER", "ADMIN"), sysRoleMapper.selectCodesByUserId(1L));
    }
}
