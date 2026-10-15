package com.smt.common.util;

/**
 * 分页边界的唯一钳制实现（page/size → 安全的 page/size/offset）。
 *
 * <p>为什么必须钳制：`(page-1)*size` 是 int 乘法，page=2147483647 时**溢出成负数**，
 * 传到 MySQL 的 `LIMIT -200, 100` 直接语法错误 → 整个请求 500（评审 R1 实测，两个服务都中）。
 *
 * <p>为什么收敛成一个类：这段逻辑原先在 {@code MeetingQueryService} 与 {@code UserService}
 * 各抄了一份，M3 的审批列表会变成第三份——三份各自演化，迟早漏改一处（评审建议"复用既有实现，
 * 别造近似副本"）。上限取 10 万 × 最大 size 100，仍远小于 {@link Integer#MAX_VALUE}，
 * 既挡住溢出，也顺带挡住深分页扫描。
 */
public record PageBounds(int page, int size, int offset) {

    public static final int MAX_PAGE = 100_000;
    public static final int DEFAULT_SIZE = 10;
    public static final int MAX_SIZE = 100;

    /** 双向钳制：小于 1 的按 1 处理（不报错——分页参数是展示细节，不值得为它打断请求） */
    public static PageBounds of(Integer page, Integer size) {
        int safePage = Math.min(Math.max(page == null ? 1 : page, 1), MAX_PAGE);
        int safeSize = Math.min(Math.max(size == null ? DEFAULT_SIZE : size, 1), MAX_SIZE);
        return new PageBounds(safePage, safeSize, (safePage - 1) * safeSize);
    }
}
