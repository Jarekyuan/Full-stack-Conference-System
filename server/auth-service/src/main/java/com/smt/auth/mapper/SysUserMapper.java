package com.smt.auth.mapper;

import com.smt.auth.entity.SysUser;
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

    /** 用户名/姓名模糊搜索（选参会人场景，最多 20 条） */
    List<SysUser> searchByKeyword(@Param("keyword") String keyword);

    /** 统计总数（分页配套 COUNT；可按部门筛选） */
    long countAll(@Param("deptId") Long deptId);

    /** 分页查询（手写 LIMIT；LEFT JOIN 部门直出 deptName；roles 由 Service 批量装配） */
    List<UserVO> selectPage(@Param("deptId") Long deptId,
                            @Param("offset") int offset,
                            @Param("size") int size);

    /** 创建用户（自增主键回填 id） */
    int insert(SysUser user);

    /** 更新状态（0 停用 / 1 启用） */
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);
}
