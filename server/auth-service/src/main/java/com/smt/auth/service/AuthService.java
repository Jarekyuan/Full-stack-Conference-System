package com.smt.auth.service;

import com.smt.auth.dto.LoginRequest;
import com.smt.auth.entity.SysDept;
import com.smt.auth.entity.SysUser;
import com.smt.auth.mapper.SysDeptMapper;
import com.smt.auth.mapper.SysRoleMapper;
import com.smt.auth.mapper.SysUserMapper;
import com.smt.auth.store.RefreshTokenStore;
import com.smt.auth.vo.LoginResponse;
import com.smt.common.context.UserContext;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.common.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 认证服务：登录 / 刷新（滚动）/ 登出 / 当前用户（T1.10-T1.11）
 * v1.3：角色改为 RBAC 多角色——签发 JWT 前查 sys_user_role 装配 roles 数组
 */
@Service
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysDeptMapper sysDeptMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenStore refreshTokenStore;
    private final Duration accessTokenTtl;

    public AuthService(SysUserMapper sysUserMapper, SysRoleMapper sysRoleMapper, SysDeptMapper sysDeptMapper,
                       JwtUtil jwtUtil, PasswordEncoder passwordEncoder,
                       RefreshTokenStore refreshTokenStore,
                       @Value("${security.jwt.access-token-ttl:30m}") Duration accessTokenTtl) {
        this.sysUserMapper = sysUserMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysDeptMapper = sysDeptMapper;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenStore = refreshTokenStore;
        this.accessTokenTtl = accessTokenTtl;
    }

    /**
     * 用户不存在时也要跑一次 BCrypt 的"陪跑"哈希。
     * 原因：`user == null || !matches(...)` 会短路，用户不存在时根本不执行 BCrypt（实测约 4ms），
     * 而用户名存在时要跑（实测约 58ms）——15 倍差异可被脚本按响应时间批量枚举出有效用户名，
     * 只统一错误码挡不住（评审 R2）。这里用固定占位哈希，密码永不可能匹配成功。
     */
    private static final String DUMMY_HASH = "$2y$10$1j9f94FzwjE4l2G/II.G/edJsIUMgUBdFyZKZqLpOQF9joswpyB/2";

    public LoginResponse login(LoginRequest request) {
        SysUser user = sysUserMapper.selectByUsername(request.getUsername());
        // 两条分支都执行一次 BCrypt（时序对齐），错误码统一 LOGIN_FAILED（防用户名枚举）
        boolean matched = passwordEncoder.matches(request.getPassword(),
                user == null ? DUMMY_HASH : user.getPassword());
        if (user == null || !matched) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        if (user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }
        return issueTokens(user);
    }

    /**
     * 刷新：原子轮换双 token（校验旧 refreshToken 与写入新 token 在同一段 Lua 内完成，旧 token 即刻失效）。
     * 并发持同一旧 token 刷新时只有一个请求成功，另一个按 20003 处理——修复评审 P0 的竞态。
     */
    public LoginResponse refresh(String refreshToken) {
        RefreshTokenStore.Rotated rotated = refreshTokenStore.rotate(refreshToken);
        if (rotated == null) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        SysUser user = sysUserMapper.selectById(rotated.userId());
        if (user == null || user.getStatus() == 0) {
            // 用户已被删除/禁用：作废刚轮换出的 token，避免残留有效凭证
            refreshTokenStore.removeByUser(rotated.userId());
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        List<String> roles = rolesOf(user.getId());
        LoginResponse response = new LoginResponse();
        response.setAccessToken(jwtUtil.generate(user.getId(), roles, accessTokenTtl));
        response.setRefreshToken(rotated.newToken());
        response.setExpiresIn(accessTokenTtl.toSeconds());
        response.setUser(toUser(user, roles));
        return response;
    }

    /** 登出：作废 refreshToken（accessToken 短效，自然过期） */
    public void logout(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        refreshTokenStore.removeByUser(userId);
    }

    /** 当前用户：身份来自网关注入的请求头（UserContext） */
    public LoginResponse.User currentUser() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        // 账号状态也要看：停用后若这里照常返回资料与角色，前端会以为登录态仍有效（评审 R3 新发现的漏点）
        if (user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }
        return toUser(user, rolesOf(user.getId()));
    }

    private LoginResponse issueTokens(SysUser user) {
        List<String> roles = rolesOf(user.getId());
        String accessToken = jwtUtil.generate(user.getId(), roles, accessTokenTtl);
        String refreshToken = UUID.randomUUID().toString();
        refreshTokenStore.save(user.getId(), refreshToken);

        LoginResponse response = new LoginResponse();
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);
        response.setExpiresIn(accessTokenTtl.toSeconds());
        response.setUser(toUser(user, roles));
        return response;
    }

    /** 用户的角色 code 列表（只含启用角色；多角色按 id 排序稳定输出） */
    private List<String> rolesOf(Long userId) {
        return sysRoleMapper.selectCodesByUserId(userId);
    }

    private LoginResponse.User toUser(SysUser user, List<String> roles) {
        LoginResponse.User vo = new LoginResponse.User();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRoles(roles);
        vo.setDeptId(user.getDeptId());
        if (user.getDeptId() != null) {
            SysDept dept = sysDeptMapper.selectById(user.getDeptId());
            vo.setDeptName(dept == null ? null : dept.getName());
        }
        vo.setAvatar(user.getAvatar());
        return vo;
    }
}
