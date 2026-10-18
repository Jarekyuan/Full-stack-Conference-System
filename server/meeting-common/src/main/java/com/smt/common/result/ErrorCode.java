package com.smt.common.result;

import lombok.Getter;

/**
 * 错误码字典（全表见 docs/04 接口设计 2.2，分段规则：1xxxx 通用 / 2xxxx 认证 / 3xxxx 会议室 / 4xxxx 预约 / 5xxxx 审批 / 6xxxx 通知 / 7xxxx 会议纪要与文件）
 * 新增码必须同步登记 04 文档
 */
@Getter
public enum ErrorCode {

    // ---- 1xxxx 通用 ----
    SYSTEM_ERROR(10000, "系统内部错误"),
    PARAM_ERROR(10001, "参数校验失败"),
    UNAUTHORIZED(10002, "未登录或登录已过期"),
    NO_PERMISSION(10003, "无权限执行此操作"),
    NOT_FOUND(10004, "资源不存在"),
    RATE_LIMITED(10005, "请求过于频繁，请稍后再试"),
    DUPLICATE_SUBMIT(10006, "请勿重复提交"),
    SYSTEM_BUSY(10007, "系统繁忙，请重试"),
    METHOD_NOT_ALLOWED(10008, "请求方法不支持"),
    UNSUPPORTED_MEDIA_TYPE(10009, "不支持的请求内容类型"),

    // ---- 2xxxx 认证与用户 ----
    LOGIN_FAILED(20001, "用户名或密码错误"),
    TOKEN_INVALID(20002, "accessToken 无效或已过期"),
    REFRESH_TOKEN_INVALID(20003, "登录状态已失效，请重新登录"),
    USER_DISABLED(20004, "账号已被禁用"),
    USERNAME_EXISTS(20005, "用户名已存在"),
    /** 防止唯一管理员自锁：停用自己 / 移除自己的 ADMIN 角色 */
    SELF_OPERATION_FORBIDDEN(20006, "不能对自己的账号执行该操作"),
    /** 最后一名启用管理员保护：降权/停用别人也不行 */
    LAST_ADMIN_PROTECTED(20007, "系统至少需要保留一个启用管理员"),

    // ---- 3xxxx 会议室 ----
    ROOM_NOT_FOUND(30001, "会议室不存在"),
    ROOM_MAINTENANCE(30002, "会议室维护中，暂不可预约"),
    OUT_OF_RESERVE_WINDOW(30003, "超出可预约时间范围"),
    DURATION_LIMIT_EXCEEDED(30004, "超出单场时长上限"),
    CANCEL_DEADLINE_PASSED(30005, "距会议开始不足可取消时限"),
    ROOM_HAS_ACTIVE_MEETINGS(30006, "该会议室存在未结束的会议，请先处理"),

    // ---- 4xxxx 预约 ----
    TIME_CONFLICT(40001, "所选时段与已有会议冲突"),
    MEETING_STATUS_NOT_ALLOWED(40002, "当前会议状态不允许该操作"),
    MEETING_NOT_FOUND(40003, "会议不存在"),
    NOT_MEETING_OWNER(40004, "只能操作自己组织的会议"),
    RECURRING_CONFLICT(40005, "周期会议存在冲突场次"),
    EXTEND_LIMIT_EXCEEDED(40006, "延长超出允许上限"),
    RECURRING_NOT_ALLOWED(40007, "该会议室未开放周期预约"),
    RECURRING_NOT_FOUND(40008, "周期会议系列不存在"),

    // ---- 5xxxx 审批 ----
    APPROVAL_CONFLICT(50001, "该时段已被占用，无法通过审批"),
    APPROVAL_ALREADY_HANDLED(50002, "该申请已被处理"),
    NO_APPROVAL_PERMISSION(50003, "无权审批该申请"),

    // ---- 6xxxx 通知 ----
    NOTIFICATION_NOT_FOUND(60001, "通知不存在"),

    // ---- 7xxxx 会议纪要与文件 ----
    MINUTES_NOT_FOUND(70001, "会议纪要不存在"),
    MINUTES_STATUS_NOT_ALLOWED(70002, "当前会议状态不能上传纪要"),
    TRANSCRIPT_FORMAT_INVALID(70003, "转写文本格式错误"),
    FILE_TYPE_NOT_ALLOWED(70004, "文件类型不支持"),
    FILE_TOO_LARGE(70005, "文件或转写文本超出大小上限"),
    UPLOAD_SESSION_NOT_FOUND(70006, "上传会话不存在或已过期"),
    UPLOAD_INCOMPLETE(70007, "分片不完整或大小不一致"),
    AI_NOT_CONFIGURED(70008, "AI 服务未配置"),
    AI_GENERATION_FAILED(70009, "纪要生成失败"),
    TRANSCRIPT_EMPTY(70010, "转写内容无有效分段");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
