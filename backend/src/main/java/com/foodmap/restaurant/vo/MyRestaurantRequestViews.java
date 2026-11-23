package com.foodmap.restaurant.vo;

import com.foodmap.review.vo.ReviewSubmissionVo;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 本人收件视角的显式白名单，不能复用包含管理快照的管理员详情。 */
public final class MyRestaurantRequestViews {
    private MyRestaurantRequestViews() {}

    @Data
    public static class Item {
        private String id;
        private String restaurantId;

        /** 驳回后重提形成的新申请指向原申请；首次提交为null。 */
        private String previousRequestId;

        private String requestType;
        private String status;

        /** 通过处理方式：CREATED、UPDATED、LINKED_EXISTING；未通过或历史记录可为空。 */
        private String approvalMode;

        private String name;
        private Instant createdAt;
        private Instant reviewedAt;

        /** 本人撤回时间；仅WITHDRAWN有值。 */
        private Instant withdrawnAt;

        private int photoCount;
    }

    @Data
    public static class Page {
        private String userId;
        private List<Item> records;
        private Map<String, Long> counts;
        private long total;
        private long totalPages;
        private int page;
        private int pageSize;
    }

    @Data
    public static class Detail {
        private String userId;
        private Item application;
        private Map<String, Object> proposed;
        private Map<String, Object> approved;
        private String rejectReason;
        private boolean targetAvailable;

        /** 仅返回申请内位置；图片地址由申请ID/位置生成，不泄露文件ID或存储键。 */
        private List<Integer> photoPositions;

        /** 仅本人详情可见；普通申请为null，管理员审核协议不包含此字段。 */
        private ReviewSubmissionVo reviewSubmission;

        /** 原驳回申请重提后的新申请ID；附带点评转移后可据此进入当前申请，未重提为null。 */
        private String resubmittedRequestId;
    }

    @Data
    public static class StatusCount {
        private String status;

        /** 通过处理方式：CREATED、UPDATED、LINKED_EXISTING；未通过或历史记录可为空。 */
        private String approvalMode;

        private long total;
    }
}
