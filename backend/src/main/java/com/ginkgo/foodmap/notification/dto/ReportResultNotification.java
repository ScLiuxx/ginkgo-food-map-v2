package com.ginkgo.foodmap.notification.dto;

import lombok.Data;

/** 举报结案事实；由moderation模块在同一事务内提供，收件人只能是原举报者。 */
@Data
public class ReportResultNotification {
    private long recipientUserId;
    private long reportId;
    /** 目标点评编号；回复举报填写父点评编号，便于前端提示内容已失效。 */
    private long reviewId;
    /** REMOVED表示确认违规并删除，DISMISSED表示未认定违规。 */
    private String decision;
}
