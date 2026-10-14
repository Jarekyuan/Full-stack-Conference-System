package com.smt.auth.service;

import cn.hutool.core.util.StrUtil;
import com.smt.auth.dto.UserCreateDTO;
import com.smt.auth.entity.SysUser;
import com.smt.auth.mapper.SysUserMapper;
import com.smt.auth.vo.UserBriefVO;
import com.smt.auth.vo.UserVO;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户服务：搜索 / 分页 / 创建 / 启用停用（T1.12）
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;

    /** 选参会人搜索：空关键字直接返回空（避免无意义全表扫描） */
    public List<UserVO> search(String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return List.of();
        }
        return sysUserMapper.searchByKeyword(keyword.trim()).stream().map(UserService::toVO).toList();
    }

    /** 批量查询用户精简信息（服务间内部接口：id → {id, realName, email}） */
    public List<UserBriefVO> batchByIds(List<Long> ids) {
        return sysUserMapper.selectByIds(ids).stream()
                .map(user -> new UserBriefVO(user.getId(), user.getRealName(), user.getEmail()))
                .toList();
    }

    public PageResult<UserVO> page(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long total = sysUserMapper.countAll();
        List<UserVO> list = sysUserMapper.selectPage((safePage - 1) * safeSize, safeSize)
                .stream().map(UserService::toVO).toList();
        return PageResult.of(list, total, safePage, safeSize);
    }

    public Long create(UserCreateDTO dto) {
        // 友好校验（并发窗口由 uk_username 唯一索引兜底）
        if (sysUserMapper.selectByUsername(dto.getUsername()) != null) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }
        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setRealName(dto.getRealName());
        user.setEmail(dto.getEmail());
        user.setRole(StrUtil.blankToDefault(dto.getRole(), "USER"));
        user.setStatus(1);
        sysUserMapper.insert(user);
        return user.getId();
    }

    public void updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "status 只能为 0（停用）或 1（启用）");
        }
        if (sysUserMapper.updateStatus(id, status) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "用户不存在");
        }
    }

    private static UserVO toVO(SysUser user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setEmail(user.getEmail());
        vo.setAvatar(user.getAvatar());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        return vo;
    }
}
