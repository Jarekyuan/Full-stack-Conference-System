package com.smt.meeting.vo;

/**
 * 系列取消结果（docs/04 4.9）。
 *
 * <p>{@code skipped} 是**有意返回**的：用户点"取消整个系列"时，系列里往往已经有过去的场次，
 * 回一句"取消了 4 场"但不解释其余几场去哪了，会让人以为漏了。
 */
public record RecurringCancelVO(int cancelled, int skipped) {
}
