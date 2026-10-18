package com.smt.meeting.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UploadedPartVO {

    private Integer partNumber;
    private String etag;
    private Long size;
}
