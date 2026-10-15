package com.smt.auth.mapper;

import com.smt.auth.entity.SysRole;
import com.smt.auth.vo.UserRoleItem;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色 Mapper（SQL 手写 XML：resources/mapper/SysRoleMapper.xml）
 */
public interface SysRoleMapper {

    /** 用户拥有的角色 code 列表（登录签发 JWT / 查询装配用；只取启用角色） */
    List<String> selectCodesByUserId(@Param("userId") Long userId);

    /** 批量：用户ID → 角色 code（分页列表装配用，一次查询避免 N+1） */
    List<UserRoleItem> selectCodesByUserIds(@Param("userIds") List<Long> userIds);

    /** 用户拥有的角色 ID 列表（列表回显用） */
    List<Long> selectIdsByUserId(@Param("userId") Long userId);

    /** 全部启用角色（用户管理分配角色下拉用） */
    List<SysRole> selectAllEnabled();

    /** 按 ID 批量查询（校验 roleIds 合法性） */
    List<SysRole> selectByIds(@Param("ids") List<Long> ids);

    /** 绑定用户角色（含 <foreach> 批量；调用方负责先清空旧关联） */
    int batchInsertUserRoles(@Param("userId") Long userId, @Param("roleIds") List<Long> roleIds);

    /** 清空用户角色关联 */
    int deleteUserRoles(@Param("userId") Long userId);
}
