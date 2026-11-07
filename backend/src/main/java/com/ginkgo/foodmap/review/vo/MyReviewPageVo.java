package com.ginkgo.foodmap.review.vo;

import com.ginkgo.foodmap.restaurant.dto.RestaurantReviewTarget;
import com.ginkgo.foodmap.user.dto.PublicAuthor;
import lombok.Data;
import java.util.List;

/** 仅当前账号可读的店铺组分页；沿用公开点评的白名单字段，不暴露幂等材料。 */
@Data
public class MyReviewPageVo {
    private PublicAuthor author;
    private List<Group> records = List.of();
    private long totalGroups;
    private long totalReviews;
    private int page;
    private int pageSize;
    private long totalPages;

    /** 当前代表及历史游标与商户详情共用语义，店铺不可见时整组不进入本响应。 */
    @Data
    public static class Group {
        private RestaurantReviewTarget restaurant;
        private ReviewPageVo.CurrentReview currentReview;
        private long historyCount;
        private String nextHistoryCursor;
    }
}
