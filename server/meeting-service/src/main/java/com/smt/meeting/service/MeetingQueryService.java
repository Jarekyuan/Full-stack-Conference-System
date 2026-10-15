package com.smt.meeting.service;

import com.smt.common.context.UserContext;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.common.result.PageResult;
import com.smt.common.util.PageBounds;
import com.smt.meeting.dto.MeetingQueryDTO;
import com.smt.meeting.dto.MeetingQueryParam;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingParticipantMapper;
import com.smt.meeting.mapper.MeetingStatusLogMapper;
import com.smt.meeting.vo.MeetingDetailVO;
import com.smt.meeting.vo.MeetingVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 会议查询服务（T2.8）：列表（多条件 + 分页）+ 详情（参会人 + 状态时间线）
 */
@Service
@RequiredArgsConstructor
public class MeetingQueryService {

    /** 可查全部会议的角色 */
    private static final Set<String> ALL_SCOPE_ROLES = Set.of("ADMIN", "APPROVER");

    private final MeetingMapper meetingMapper;
    private final MeetingParticipantMapper participantMapper;
    private final MeetingStatusLogMapper statusLogMapper;

    /**
     * 列表：scope=mine（我组织的或参与的，默认）不需要额外权限；
     * scope=all（全量）仅 ADMIN/APPROVER —— 权限校验在 Service 层（SQL 层不加归属条件）。
     */
    public PageResult<MeetingVO> page(MeetingQueryDTO query) {
        // 分页边界统一由 PageBounds 钳制（page 溢出会让 LIMIT 报错 → 500，评审 R1）
        PageBounds bounds = PageBounds.of(query.getPage(), query.getSize());

        String scope = "all".equals(query.getScope()) ? "all" : "mine";
        if ("all".equals(scope)
                && UserContext.getRoles().stream().noneMatch(ALL_SCOPE_ROLES::contains)) {
            throw new BusinessException(ErrorCode.NO_PERMISSION);
        }

        MeetingQueryParam param = new MeetingQueryParam();
        param.setScope(scope);
        param.setUserId(UserContext.getUserId());
        param.setStatuses(query.parseStatuses());
        param.setRoomId(query.getRoomId());
        param.setStartDate(query.getStartDate());
        param.setEndDate(query.getEndDate());
        param.setKeyword(query.getKeyword());
        param.setOffset(bounds.offset());
        param.setSize(bounds.size());

        long total = meetingMapper.countPage(param);
        List<MeetingVO> list = total == 0 ? List.of() : meetingMapper.selectPage(param);
        return PageResult.of(list, total, bounds.page(), bounds.size());
    }

    /**
     * 详情：参会人明细 + 状态时间线。
     * 设计注明（docs/05 T2.8 验收）：会议信息**公开可查**（会议无隐私约定）——
     * 不限制"非参与人"访问，故此处不做归属校验（与"取消/修改"等写操作不同，那些需归属校验）。
     */
    public MeetingDetailVO detail(Long id) {
        MeetingDetailVO detail = meetingMapper.selectDetailById(id);
        if (detail == null) {
            throw new BusinessException(ErrorCode.MEETING_NOT_FOUND);
        }
        detail.setParticipants(participantMapper.selectDetailByMeetingId(id));
        detail.setLogs(statusLogMapper.selectTimeline(id));
        return detail;
    }
}
