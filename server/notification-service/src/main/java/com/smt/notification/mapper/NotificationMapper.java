package com.smt.notification.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.smt.notification.entity.Notification;

/**
 * 站内通知 Mapper（SQL 手写 XML：resources/mapper/NotificationMapper.xml）
 */
public interface NotificationMapper {

    /** 批量写入（一次事件 → 多个收件人；同一事务内，要么都成、要么都不成） */
    int insertBatch(@Param("list") List<Notification> notifications);
}
