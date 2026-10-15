package com.smt.auth.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门树节点（docs/04：GET /api/depts/tree）
 */
@Data
public class DeptVO {

    private Long id;
    private Long parentId;
    private String name;
    private Integer sortOrder;
    private Integer status;
    /** 子部门（树组装，默认空列表便于前端渲染） */
    private List<DeptVO> children = new ArrayList<>();
}
