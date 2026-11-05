package com.ginkgo.foodmap.restaurant.dto;

import lombok.Data;
import java.util.List;

/** ES仅交付有序身份和版本；正式展示字段由MySQL同一快照批量回查。 */
@Data
public class RestaurantSearchResult {
    private long total;
    private List<Hit> hits;

    /** 固定ID与每店版本共同用于拒绝陈旧候选。 */
    @Data
    public static class Hit {
        private long id;
        private long projectionVersion;
    }
}
