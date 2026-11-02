package com.ginkgo.foodmap.restaurant.vo;

import java.math.BigDecimal;
import lombok.Data;

/** 地图点位白名单，不返回持久化Entity、账号或管理字段；评分沿用权威统计。 */
@Data
public class RestaurantMapPointVo {
    private String id;
    private String name;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal score;
    private Integer pricePerPerson;
    private String businessStatus;
}
