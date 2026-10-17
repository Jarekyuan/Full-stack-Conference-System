package com.smt.auth.mapper;

import com.smt.auth.entity.SysDept;
import com.smt.auth.vo.DeptVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 部门 Mapper（SQL 手写 XML：resources/mapper/SysDeptMapper.xml）
 * 部门数量级小（单页树），树组装在 Service 层完成，不写递归 SQL
 */
public interface SysDeptMapper {

    /** 全量部门（按 parent_id, sort_order 排序；数据量小，树组装由 Service 做） */
    List<SysDept> selectAll();

    /**
     * 全量部门 + 直属成员数（树展示专用，T1.20）。
     * 计数用子查询直出（部门量小），避免前端为每个节点各发一次请求，也避免 Service 里 N+1。
     */
    List<DeptVO> selectTreeNodes();

    SysDept selectById(@Param("id") Long id);

    int insert(SysDept dept);

    int update(SysDept dept);

    int deleteById(@Param("id") Long id);

    /** 子部门数（删除前校验） */
    int countChildren(@Param("parentId") Long parentId);

    /** 挂靠用户数（删除前校验） */
    int countUsers(@Param("deptId") Long deptId);
}
