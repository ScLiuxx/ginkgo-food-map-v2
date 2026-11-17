package com.foodmap.restaurant.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 标签读取实体；批量查询按餐厅和位置排序，避免逐餐厅查询和分页计数膨胀。 */
@Data
@TableName("restaurant_tags")
public class RestaurantTagEntity {
    private Long restaurantId;
    private String tag;
    private int position;
}
