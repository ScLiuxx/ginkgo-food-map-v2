package com.ginkgo.foodmap.growth.dto;

import lombok.Data;

/** 由点评模块在业务事务内构造的贡献事实；身份、资源ID来自服务端，不接受客户端金额。 */
@Data
public class FirstStoreReviewContribution {
    private long userId;
    private long restaurantId;

    /** 窗口和餐厅互斥；旧调用默认普通餐厅，不改变既有奖励键。 */
    private Long windowId;

    private long reviewId;
}
