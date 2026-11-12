package com.ginkgo.foodmap.restaurant.entity;

import lombok.Data;

import java.math.BigDecimal;

/** restaurant私有榜单候选，只含可见且营业普通餐厅的名称、确认坐标及可公开封面ID。 */
@Data
public class RestaurantRankingCandidate {
    private long id;
    private String name;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private Long coverPhotoId;
}
