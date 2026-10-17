package com.smt.auth.mapper;

import com.smt.auth.entity.SysUser;
import com.smt.auth.vo.UserSearchVO;
import com.smt.auth.vo.UserVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户表 Mapper（SQL 一律手写 XML：resources/mapper/SysUserMapper.xml）
 */
public interface SysUserMapper {

    /** 按用户名查询（登录用，命中唯一索引 uk_username） */
    SysUser selectByUsername(@Param("username") String username);

    /** 按主键查询（refresh/me 用） */
    SysUser selectById(@Param("id") Long id);

    /** 批量按主键查询（IN + foreach；内部接口最多 100 个 id） */
    List<SysUser> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 用户名/姓名模糊搜索 + 可选的部门过滤（选参会人场景，最多 50 条；LEFT JOIN 部门直出 deptName）。
     * keyword 为空、deptId 有值时 = 列出该部门直属成员（T6.1 部门树选人）。
     */
    List<UserSearchVO> searchBrief(@Param("keyword") String keyword,
                                   @Param("deptId") Long deptId);

    /**
     * 统计总数（分页配套 COUNT）。
     * deptIds 为 null = 全部；否则限定在这些部门内（T1.20 的 includeChildren 已在 Service 展开成 id 列表）。
     */
    long countAll(@Param("deptIds") List<Long> deptIds);

    /** 分页查询（手写 LIMIT；LEFT JOIN 部门直出 deptName；roles 由 Service 批量装配） */
    List<UserVO> selectPage(@Param("deptIds") List<Long> deptIds,
                            @Param("offset") int offset,
                            @Param("size") int size);

    /** 创建用户（自增主键回填 id） */
    int insert(SysUser user);

    /** 更新状态（0 停用 / 1 启用） */
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /** 编辑用户资料（T1.20：姓名/邮箱/部门；用户名与密码不在此接口） */
    int updateProfile(SysUser user);
}
