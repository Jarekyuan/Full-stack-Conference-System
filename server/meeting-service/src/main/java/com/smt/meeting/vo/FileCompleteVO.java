package com.smt.meeting.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FileCompleteVO {

    private Long fileId;
    private String objectKey;
    private Long sizeBytes;
}
