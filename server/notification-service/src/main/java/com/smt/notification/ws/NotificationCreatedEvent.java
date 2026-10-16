package com.smt.notification.ws;

import java.util.List;

import com.smt.notification.entity.Notification;

/**
 * "通知已落库"的领域事件（T4.6）：由 {@code NotificationService.handle} 在**事务内**发布，
 * 由 {@link NotificationPushListener} 在**事务提交后**推送给在线用户。
 *
 * <p>为什么不直接调用推送：推送是不可回滚的副作用，而"推送"与"落库"之间只要不是同一个提交点，
 * 就必然有一种坏情况——先推后落库，事务回滚了推送还在（用户点开一条**不存在**的通知）；
 * 先落库后推，推送失败就只是少推（下次拉列表照样有）。
 * 与 T4.1 的 MQ 事件是同一个道理，所以这里沿用同一套写法（AFTER_COMMIT + 必须 try/catch）。
 *
 * @param rows 本次落库的通知（**已回填主键**——推送体里要带 id，前端"标记已读"要用它）
 */
public record NotificationCreatedEvent(List<Notification> rows) {
}
