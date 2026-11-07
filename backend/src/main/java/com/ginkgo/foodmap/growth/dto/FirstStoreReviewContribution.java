package com.ginkgo.foodmap.growth.dto;

import lombok.Data;

/** 由点评模块在业务事务内构造的贡献事实；身份、资源ID来自服务端，不接受客户端金额。 */
@Data
public class FirstStoreReviewContribution {
    private long userId;
    private long restaurantId;
    private long reviewId;
}
