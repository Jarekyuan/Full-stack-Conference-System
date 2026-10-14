package com.smt.auth.mapper;

import com.smt.auth.entity.SysUser;
import org.apache.ibatis.annotations.Param;

/**
 * 用户表 Mapper（SQL 一律手写 XML：resources/mapper/SysUserMapper.xml）
 */
public interface SysUserMapper {

    /** 按用户名查询（登录用，命中唯一索引 uk_username） */
    SysUser selectByUsername(@Param("username") String username);
}
