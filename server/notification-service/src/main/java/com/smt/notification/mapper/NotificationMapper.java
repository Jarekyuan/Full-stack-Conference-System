package com.smt.notification.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.smt.notification.entity.Notification;
import com.smt.notification.vo.NotificationVO;

/**
 * 站内通知 Mapper（SQL 手写 XML：resources/mapper/NotificationMapper.xml）
 *
 * <p>**每个方法都带 userId 条件**（除按主键查的那条）：站内信是私有的，
 * 归属过滤放在 SQL 里而不是"先查出来再在 Java 里比对"——后者一旦有人漏写 if，就是越权读。
 */
public interface NotificationMapper {

    /** 批量写入（一次事件 → 多个收件人；同一事务内，要么都成、要么都不成） */
    int insertBatch(@Param("list") List<Notification> notifications);

    /** 我的通知（分页，时间倒序）。排序见 XML：created_at DESC, id DESC */
    List<NotificationVO> selectPage(@Param("userId") Long userId,
                                    @Param("offset") int offset,
                                    @Param("size") int size);

    long countByUser(@Param("userId") Long userId);

    /** 未读数（顶栏红点） */
    long countUnread(@Param("userId") Long userId);

    /** 单条已读；返回**匹配行数**（0 = 这条不是我的或不存在） */
    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    /** 全部已读；返回更新行数 */
    int markAllRead(@Param("userId") Long userId);

    /** 按主键查（仅用于已读接口判断"0 行"的原因：不是我的 vs 已经读过） */
    Notification selectById(@Param("id") Long id);
}
