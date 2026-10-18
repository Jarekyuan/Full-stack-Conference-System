package com.smt.meeting.mapper;

import com.smt.meeting.entity.MinutesSpeaker;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 纪要发言人 Mapper（SQL 手写 XML：resources/mapper/MinutesSpeakerMapper.xml）
 */
public interface MinutesSpeakerMapper {

    /** 按纪要查询发言人（ORDER BY id） */
    List<MinutesSpeaker> selectByMinutesId(@Param("minutesId") Long minutesId);

    /** 删除某纪要的全部发言人（重新导入前清空） */
    int deleteByMinutesId(@Param("minutesId") Long minutesId);

    /** 批量新增（<foreach> 生成多值 INSERT） */
    int batchInsert(@Param("list") List<MinutesSpeaker> speakers);

    /** 修改展示名与展示色（命中 uk_minutes_speaker） */
    int updateDisplayName(@Param("minutesId") Long minutesId,
                          @Param("speakerKey") String speakerKey,
                          @Param("displayName") String displayName,
                          @Param("color") String color);
}
