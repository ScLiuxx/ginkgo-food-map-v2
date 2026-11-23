package com.foodmap.restaurant.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 公开榜单字段；榜单独立使用五分制，不输出内部排序分、规则、原始累计分或用户名单。 */
@Data
public class RestaurantRankingVo {
    private String campusCode;
    private String periodType;
    /** READY包含成功空榜；PREPARING无快照；UNAVAILABLE表示已有快照超过展示上限。 */
    private String status;
    private int scoreScale = 5;
    private Instant windowStartAt;
    private Instant windowEndAt;
    private Instant generatedAt;
    private List<Item> items = List.of();

    /** ID使用字符串；封面只保存本站稳定路径，不在每次读榜时签发OSS地址或逐店查库。 */
    @Data
    public static class Item {
        private int rank;
        private String restaurantId;
        private String name;
        private String coverUrl;
        private BigDecimal tasteScore;
        /** 对应周期的五分制平滑推荐分，与排序依据一致；不同于历史长期口碑分。 */
        private BigDecimal recommendationScore;
        private long participantCount;
    }
}
