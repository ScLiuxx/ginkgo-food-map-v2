package com.foodmap.restaurant.dto;

import lombok.Data;
import java.math.BigDecimal;

/** restaurant公开统计协作输入，累计分增量由review计算；不作为HTTP可写字段。 */
@Data
public class RestaurantReviewDelta {
    /** 发布默认加一，删除显式减一；与其他累计值同一SQL原子更新。 */
    private int reviews = 1;
    private int participants;
    private int recommendations;
    private BigDecimal taste;
    private BigDecimal value;
    private BigDecimal service;
    private BigDecimal ambience;
    private BigDecimal overall;
}
