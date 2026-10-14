package com.smt.auth.service;

import com.smt.auth.dto.LoginRequest;
import com.smt.auth.entity.SysUser;
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
import java.util.UUID;

/**
 * 认证服务：登录 / 刷新（滚动）/ 登出 / 当前用户（T1.10-T1.11）
 */
@Service
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenStore refreshTokenStore;
    private final Duration accessTokenTtl;

    public AuthService(SysUserMapper sysUserMapper, JwtUtil jwtUtil, PasswordEncoder passwordEncoder,
                       RefreshTokenStore refreshTokenStore,
                       @Value("${security.jwt.access-token-ttl:30m}") Duration accessTokenTtl) {
        this.sysUserMapper = sysUserMapper;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenStore = refreshTokenStore;
        this.accessTokenTtl = accessTokenTtl;
    }

    public LoginResponse login(LoginRequest request) {
        SysUser user = sysUserMapper.selectByUsername(request.getUsername());
        // 用户不存在与密码错误返回同一错误码（防用户名枚举）
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
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
        LoginResponse response = new LoginResponse();
        response.setAccessToken(jwtUtil.generate(user.getId(), user.getRole(), accessTokenTtl));
        response.setRefreshToken(rotated.newToken());
        response.setExpiresIn(accessTokenTtl.toSeconds());
        response.setUser(toUser(user));
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
        return toUser(user);
    }

    private LoginResponse issueTokens(SysUser user) {
        String accessToken = jwtUtil.generate(user.getId(), user.getRole(), accessTokenTtl);
        String refreshToken = UUID.randomUUID().toString();
        refreshTokenStore.save(user.getId(), refreshToken);

        LoginResponse response = new LoginResponse();
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);
        response.setExpiresIn(accessTokenTtl.toSeconds());
        response.setUser(toUser(user));
        return response;
    }

    private LoginResponse.User toUser(SysUser user) {
        LoginResponse.User vo = new LoginResponse.User();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRole(user.getRole());
        vo.setAvatar(user.getAvatar());
        return vo;
    }
}
