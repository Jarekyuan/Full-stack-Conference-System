package com.smt.auth.service;

import com.smt.auth.dto.UserUpdateDTO;
import com.smt.auth.entity.SysDept;
import com.smt.auth.entity.SysRole;
import com.smt.auth.entity.SysUser;
import com.smt.auth.mapper.SysDeptMapper;
import com.smt.auth.mapper.SysRoleMapper;
import com.smt.auth.mapper.SysUserMapper;
import com.smt.auth.store.RefreshTokenStore;
import com.smt.common.context.LoginUser;
import com.smt.common.context.UserContext;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1.20：用户编辑 / 部门子树筛选 / 自锁保护。
 *
 * <p>为什么值得单测：编辑用户是**三条写路径（资料、角色关联、部门绑定）的组合**，
 * "全量替换角色"只要漏掉 delete 就会撞 uk_user_role；而自锁保护只在"编辑自己"时触发，
 * 脚本很难稳定构造（需要把自己降权/停用）——这两类正好是单测该钉住的。
 */
class UserServiceTest {

    private static final Long TARGET_ID = 10L;
    private static final Long OPERATOR_ID = 1L;

    private SysUserMapper sysUserMapper;
    private SysRoleMapper sysRoleMapper;
    private SysDeptMapper sysDeptMapper;
    private RefreshTokenStore refreshTokenStore;
    private UserService service;

    @BeforeEach
    void setUp() {
        sysUserMapper = mock(SysUserMapper.class);
        sysRoleMapper = mock(SysRoleMapper.class);
        sysDeptMapper = mock(SysDeptMapper.class);
        refreshTokenStore = mock(RefreshTokenStore.class);
        service = new UserService(sysUserMapper, sysRoleMapper, sysDeptMapper,
                mock(PasswordEncoder.class), refreshTokenStore);
    }

    @AfterEach
    void tearDown() {
        // UserContext 是 ThreadLocal：不清会串到下一个用例（同 JVM 内测试线程复用）
        UserContext.clear();
    }

    private SysUser user() {
        SysUser user = new SysUser();
        user.setId(TARGET_ID);
        user.setUsername("zhangsan");
        user.setRealName("张三");
        return user;
    }

    private SysDept dept(Long id, Long parentId) {
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setParentId(parentId);
        dept.setName("部门" + id);
        return dept;
    }

    private SysRole role(Long id, String code) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setCode(code);
        return role;
    }

    private UserUpdateDTO updateDTO(List<Long> roleIds, Long deptId) {
        UserUpdateDTO dto = new UserUpdateDTO();
        dto.setRealName("张三改");
        dto.setEmail("zhangsan@example.com");
        dto.setRoleIds(roleIds);
        dto.setDeptId(deptId);
        return dto;
    }

    @Test
    void updateShouldReplaceProfileAndRoles() {
        when(sysUserMapper.selectById(TARGET_ID)).thenReturn(user());
        when(sysDeptMapper.selectById(2L)).thenReturn(dept(2L, 1L));
        when(sysRoleMapper.selectByIds(List.of(1L, 2L)))
                .thenReturn(List.of(role(1L, "USER"), role(2L, "APPROVER")));

        service.update(TARGET_ID, updateDTO(List.of(1L, 2L), 2L));

        ArgumentCaptor<SysUser> profile = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).updateProfile(profile.capture());
        assertThat(profile.getValue().getId()).isEqualTo(TARGET_ID);
        assertThat(profile.getValue().getRealName()).isEqualTo("张三改");
        assertThat(profile.getValue().getEmail()).isEqualTo("zhangsan@example.com");
        assertThat(profile.getValue().getDeptId()).isEqualTo(2L);
        // 全量替换：必须先清旧关联，否则重复角色会撞 uk_user_role
        verify(sysRoleMapper).deleteUserRoles(TARGET_ID);
        verify(sysRoleMapper).batchInsertUserRoles(TARGET_ID, List.of(1L, 2L));
    }

    @Test
    void updateShouldRejectMissingDept() {
        when(sysUserMapper.selectById(TARGET_ID)).thenReturn(user());
        when(sysDeptMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(TARGET_ID, updateDTO(List.of(1L), 999L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        verify(sysUserMapper, never()).updateProfile(any());
    }

    @Test
    void updateShouldRejectMissingRole() {
        when(sysUserMapper.selectById(TARGET_ID)).thenReturn(user());
        when(sysRoleMapper.selectByIds(List.of(999L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.update(TARGET_ID, updateDTO(List.of(999L), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        verify(sysRoleMapper, never()).deleteUserRoles(any());
    }

    @Test
    void selfDemoteShouldFailWith20006() {
        UserContext.set(new LoginUser(OPERATOR_ID, List.of("ADMIN")));
        when(sysUserMapper.selectById(OPERATOR_ID)).thenReturn(user());
        when(sysRoleMapper.selectByIds(List.of(1L))).thenReturn(List.of(role(1L, "USER")));

        assertThatThrownBy(() -> service.update(OPERATOR_ID, updateDTO(List.of(1L), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SELF_OPERATION_FORBIDDEN.getCode());
        // 自锁保护必须挡在写库之前
        verify(sysUserMapper, never()).updateProfile(any());
        verify(sysRoleMapper, never()).deleteUserRoles(any());
    }

    @Test
    void selfDemoteWithAdminStillPresentShouldPass() {
        UserContext.set(new LoginUser(OPERATOR_ID, List.of("ADMIN")));
        when(sysUserMapper.selectById(OPERATOR_ID)).thenReturn(user());
        when(sysRoleMapper.selectByIds(List.of(2L, 3L)))
                .thenReturn(List.of(role(2L, "APPROVER"), role(3L, "ADMIN")));

        service.update(OPERATOR_ID, updateDTO(List.of(2L, 3L), null));

        verify(sysUserMapper).updateProfile(any());
        verify(sysRoleMapper).batchInsertUserRoles(OPERATOR_ID, List.of(2L, 3L));
    }

    @Test
    void selfDisableShouldFailWith20006() {
        UserContext.set(new LoginUser(OPERATOR_ID, List.of("ADMIN")));

        assertThatThrownBy(() -> service.updateStatus(OPERATOR_ID, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SELF_OPERATION_FORBIDDEN.getCode());
        verify(sysUserMapper, never()).updateStatus(any(), any());
    }

    @Test
    void disableShouldRevokeRefreshToken() {
        when(sysUserMapper.updateStatus(TARGET_ID, 0)).thenReturn(1);

        service.updateStatus(TARGET_ID, 0);

        // 停用后 refreshToken 立即作废；accessToken 最长 30 分钟窗口是文档化的取舍
        verify(refreshTokenStore).removeByUser(TARGET_ID);
    }

    @Test
    void enableShouldNotRevokeRefreshToken() {
        when(sysUserMapper.updateStatus(TARGET_ID, 1)).thenReturn(1);

        service.updateStatus(TARGET_ID, 1);

        verify(refreshTokenStore, never()).removeByUser(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageShouldExpandSubtreeWhenIncludeChildren() {
        when(sysDeptMapper.selectAll()).thenReturn(List.of(
                dept(1L, 0L), dept(2L, 1L), dept(3L, 2L), dept(9L, 0L)));
        when(sysUserMapper.countAll(any())).thenReturn(0L);
        when(sysUserMapper.selectPage(any(), anyInt(), anyInt())).thenReturn(List.of());

        service.page(1, 10, 1L, true);

        ArgumentCaptor<List<Long>> deptIds = ArgumentCaptor.forClass(List.class);
        verify(sysUserMapper).countAll(deptIds.capture());
        // 1 → 2 → 3 整棵子树；兄弟 9 不能被捎带
        assertThat(deptIds.getValue()).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageWithoutChildrenShouldOnlyUseExactDept() {
        when(sysUserMapper.countAll(any())).thenReturn(0L);
        when(sysUserMapper.selectPage(any(), anyInt(), anyInt())).thenReturn(List.of());

        service.page(1, 10, 2L, false);

        ArgumentCaptor<List<Long>> deptIds = ArgumentCaptor.forClass(List.class);
        verify(sysUserMapper).countAll(deptIds.capture());
        assertThat(deptIds.getValue()).containsExactly(2L);
        verify(sysDeptMapper, never()).selectAll();
    }

    // ———— T6.1：选参会人搜索 / 部门树选人 ————

    @Test
    void searchShouldReturnEmptyWhenNoKeywordAndNoDept() {
        assertThat(service.search("  ", null)).isEmpty();
        // 空条件不能打库：否则会变成"全表扫描 + 把整个通讯录返回"
        verify(sysUserMapper, never()).searchBrief(any(), any());
    }

    @Test
    void searchByDeptShouldListDirectMembers() {
        service.search(null, 2L);
        verify(sysUserMapper).searchBrief(null, 2L);
    }

    @Test
    void searchByKeywordAndDeptShouldPassBoth() {
        service.search(" 张 ", 2L);
        verify(sysUserMapper).searchBrief("张", 2L);
    }

    @Test
    void searchByKeywordOnlyShouldPassNullDept() {
        service.search("张", null);
        verify(sysUserMapper).searchBrief("张", null);
    }
}
