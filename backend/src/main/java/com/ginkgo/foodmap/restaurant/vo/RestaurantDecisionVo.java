package com.ginkgo.foodmap.restaurant.vo;

import lombok.Data;
import java.time.Instant;

/** 审核回执来自已提交的申请记录；重复请求不覆盖首次审核人和时间。 */
@Data
public class RestaurantDecisionVo {
    private String id;
    private String status;
    private String restaurantId;
    private String reviewedBy;
    private Instant reviewedAt;
    private String rejectReason;
    private boolean replayed;
    /** 本次申请实际发布的正式图片；旧版已通过申请及驳回为空，不自动补发布历史图。 */
    private java.util.List<RestaurantPhotoViews.Item> publishedPhotos;
}
