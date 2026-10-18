package com.smt.meeting.mapper;

import com.smt.meeting.entity.TranscriptSegment;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 转写分段 Mapper（SQL 手写 XML：resources/mapper/TranscriptSegmentMapper.xml）
 */
public interface TranscriptSegmentMapper {

    /** 按纪要查询分段（ORDER BY seq，命中 uk_minutes_seq） */
    List<TranscriptSegment> selectByMinutesId(@Param("minutesId") Long minutesId);

    /** 删除某纪要的全部转写分段（重新导入前清空） */
    int deleteByMinutesId(@Param("minutesId") Long minutesId);

    /** 批量新增（<foreach> 生成多值 INSERT） */
    int batchInsert(@Param("list") List<TranscriptSegment> segments);

    /** 某纪要的分段总数 */
    long countByMinutesId(@Param("minutesId") Long minutesId);
}
