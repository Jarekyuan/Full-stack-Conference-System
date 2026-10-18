package com.smt.meeting.vo;

import lombok.Data;

import java.util.List;

@Data
public class MinutesSummaryVO {

    private String summary;
    private List<String> keyPoints;
    private List<DecisionVO> decisions;
    private List<ActionItemVO> actionItems;
    private List<String> risks;

    @Data
    public static class DecisionVO {
        private String content;
        private String context;
    }

    @Data
    public static class ActionItemVO {
        private String task;
        private String owner;
        private String dueDate;
        private String priority;
    }
}
