package com.smt.meeting.service;

import com.smt.common.context.UserContext;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.common.result.PageResult;
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

    /** 页码上限：防 offset 乘法溢出（上限 × 最大 size 仍远小于 Integer.MAX_VALUE），也顺带挡住深分页扫描 */
    private static final int MAX_PAGE = 100_000;

    /**
     * 列表：scope=mine（我组织的或参与的，默认）不需要额外权限；
     * scope=all（全量）仅 ADMIN/APPROVER —— 权限校验在 Service 层（SQL 层不加归属条件）。
     */
    public PageResult<MeetingVO> page(MeetingQueryDTO query) {
        // page 必须双向钳制：只设下限时 page=2147483647 会让 offset 乘法溢出成负数，
        // 传到 MySQL 的 LIMIT 直接报错 → 500（评审 R1 实测）。上限按"总条数规模"给足即可。
        int safePage = Math.min(Math.max(query.getPage() == null ? 1 : query.getPage(), 1), MAX_PAGE);
        int safeSize = Math.min(Math.max(query.getSize() == null ? 10 : query.getSize(), 1), 100);

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
        param.setOffset((safePage - 1) * safeSize);
        param.setSize(safeSize);

        long total = meetingMapper.countPage(param);
        List<MeetingVO> list = total == 0 ? List.of() : meetingMapper.selectPage(param);
        return PageResult.of(list, total, safePage, safeSize);
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
