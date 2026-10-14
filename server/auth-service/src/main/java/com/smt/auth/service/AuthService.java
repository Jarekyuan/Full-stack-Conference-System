package com.smt.auth.service;

import com.smt.auth.dto.LoginRequest;
import com.smt.auth.entity.SysUser;
import com.smt.auth.mapper.SysUserMapper;
import com.smt.auth.vo.LoginResponse;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.common.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 认证服务：登录（T1.10）
 */
@Service
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final Duration accessTokenTtl;

    public AuthService(SysUserMapper sysUserMapper, JwtUtil jwtUtil, PasswordEncoder passwordEncoder,
                       @Value("${security.jwt.access-token-ttl:30m}") Duration accessTokenTtl) {
        this.sysUserMapper = sysUserMapper;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
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

        String accessToken = jwtUtil.generate(user.getId(), user.getRole(), accessTokenTtl);

        LoginResponse response = new LoginResponse();
        response.setAccessToken(accessToken);
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
