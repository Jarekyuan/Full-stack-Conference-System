package com.smt.meeting.controller;

import com.smt.common.result.Result;
import com.smt.meeting.dto.RoomSaveDTO;
import com.smt.meeting.dto.StrategyDTO;
import com.smt.meeting.service.RoomService;
import com.smt.meeting.vo.RoomVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会议室接口（对外路径 /api/rooms/**）
 * 查询：全角色；管理（增/改/删/策略）：ADMIN
 */
@RestController
@RequestMapping("/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    // ---------- 查询（T1.15） ----------

    /** 列表（筛选：groupId 精确 / capacity 下限 / status 精确） */
    @GetMapping
    public Result<List<RoomVO>> list(@RequestParam(required = false) Long groupId,
                                     @RequestParam(required = false) Integer capacity,
                                     @RequestParam(required = false) Integer status) {
        return Result.success(roomService.list(groupId, capacity, status));
    }

    /** 详情（含分组名与策略） */
    @GetMapping("/{id}")
    public Result<RoomVO> detail(@PathVariable Long id) {
        return Result.success(roomService.detail(id));
    }

    // ---------- 管理（T1.16，ADMIN） ----------

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> create(@Valid @RequestBody RoomSaveDTO dto) {
        return Result.success(roomService.create(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody RoomSaveDTO dto) {
        roomService.update(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> remove(@PathVariable Long id) {
        roomService.remove(id);
        return Result.success();
    }

    /** 策略配置（全量更新） */
    @PutMapping("/{id}/strategy")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateStrategy(@PathVariable Long id, @Valid @RequestBody StrategyDTO dto) {
        roomService.updateStrategy(id, dto);
        return Result.success();
    }
}
