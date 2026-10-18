package com.smt.meeting.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MinutesPermissionVO {

    private Boolean canManage;
    private Boolean canView;
}
