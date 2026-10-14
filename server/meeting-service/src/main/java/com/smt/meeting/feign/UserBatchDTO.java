package com.smt.meeting.feign;

import java.util.List;

/**
 * 批量查询入参（auth-service /users/batch 契约镜像）
 */
public record UserBatchDTO(List<Long> ids) {
}
