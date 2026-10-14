package com.smt.meeting.feign;

/**
 * 用户精简信息（auth-service /users/batch 契约镜像——服务间各自持有契约副本，不跨服务依赖代码）
 */
public record UserBriefVO(Long id, String realName, String email) {
}
