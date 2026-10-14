package com.smt.auth.controller;

import com.smt.auth.dto.UserCreateDTO;
import com.smt.auth.service.UserService;
import com.smt.auth.vo.UserVO;
import com.smt.common.result.PageResult;
import com.smt.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 用户接口（对外路径 /api/users/**）
 * 搜索：全角色；管理操作：ADMIN（@PreAuthorize 方法级鉴权，身份由网关注入）
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 选参会人搜索 */
    @GetMapping("/search")
    public Result<List<UserVO>> search(@RequestParam String keyword) {
        return Result.success(userService.search(keyword));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<UserVO>> page(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return Result.success(userService.page(page, size));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> create(@Valid @RequestBody UserCreateDTO dto) {
        return Result.success(userService.create(dto));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        userService.updateStatus(id, status);
        return Result.success();
    }
}
