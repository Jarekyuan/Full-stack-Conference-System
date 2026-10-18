package com.smt.meeting.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class TranscriptVO {

    private List<TranscriptSegmentVO> segments;
}
