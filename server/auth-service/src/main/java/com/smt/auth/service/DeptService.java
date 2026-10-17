package com.smt.auth.service;

import com.smt.auth.dto.DeptSaveDTO;
import com.smt.auth.entity.SysDept;
import com.smt.auth.mapper.SysDeptMapper;
import com.smt.auth.vo.DeptVO;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门服务（v1.3）：树查询 + CRUD
 * 部门数量级小（单页树），树组装在内存完成，不写递归 SQL。
 * 用途仅"组织结构"：组织与筛选用户，不参与权限判定。
 */
@Service
@RequiredArgsConstructor
public class DeptService {

    private final SysDeptMapper sysDeptMapper;

    /** 部门树：parent_id=0 为根；父节点缺失的脏数据按根展示（不静默丢数据） */
    public List<DeptVO> tree() {
        // 树节点直接带直属成员数（T1.20）：SQL 子查询一次查完，Service 只做父子组装
        List<DeptVO> all = sysDeptMapper.selectTreeNodes();
        Map<Long, DeptVO> nodes = new LinkedHashMap<>();
        all.forEach(dept -> nodes.put(dept.getId(), dept));

        List<DeptVO> roots = new ArrayList<>();
        for (DeptVO node : nodes.values()) {
            if (node.getParentId() == null || node.getParentId() == 0) {
                roots.add(node);
            } else {
                DeptVO parent = nodes.get(node.getParentId());
                if (parent != null) {
                    parent.getChildren().add(node);
                } else {
                    roots.add(node);
                }
            }
        }
        return roots;
    }

    @Transactional
    public Long create(DeptSaveDTO dto) {
        Long parentId = dto.getParentId() == null ? 0L : dto.getParentId();
        if (parentId != 0 && sysDeptMapper.selectById(parentId) == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "父部门不存在");
        }
        SysDept dept = new SysDept();
        dept.setParentId(parentId);
        dept.setName(dto.getName());
        dept.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        dept.setStatus(1);
        sysDeptMapper.insert(dept);
        return dept.getId();
    }

    /** 修改（仅名称/排序/状态；父部门不可变，避免子树移动的环校验复杂度） */
    public void update(Long id, DeptSaveDTO dto) {
        if (sysDeptMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "部门不存在");
        }
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setName(dto.getName());
        dept.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        dept.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        sysDeptMapper.update(dept);
    }

    /** 删除：存在子部门或挂靠用户时拒绝（应用层保证完整性，同"无外键"约定） */
    @Transactional
    public void delete(Long id) {
        if (sysDeptMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "部门不存在");
        }
        if (sysDeptMapper.countChildren(id) > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "存在子部门，无法删除");
        }
        if (sysDeptMapper.countUsers(id) > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "部门下存在用户，无法删除");
        }
        sysDeptMapper.deleteById(id);
    }

}
