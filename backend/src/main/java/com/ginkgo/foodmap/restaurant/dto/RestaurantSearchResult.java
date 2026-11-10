package com.ginkgo.foodmap.restaurant.dto;

import com.ginkgo.foodmap.restaurant.vo.RestaurantVo;

import lombok.Data;

import java.util.List;

/** ES交付有序身份和列表投影；列表允许最终一致性，地图旧路径仍可回查身份。 */
@Data
public class RestaurantSearchResult {
    private long total;
    private List<Hit> hits;
    private List<RestaurantVo> records = List.of();

    /** 固定ID与每店版本共同用于拒绝陈旧候选。 */
    @Data
    public static class Hit {
        private long id;
        private long projectionVersion;
    }
}
