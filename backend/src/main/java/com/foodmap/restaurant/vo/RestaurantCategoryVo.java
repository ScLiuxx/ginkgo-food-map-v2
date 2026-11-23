package com.foodmap.restaurant.vo;

import lombok.Data;

/** 公开分类字典；enabled=false 的历史分类仍可用于阅读筛选，不代表可用于新建。 */
@Data
public class RestaurantCategoryVo {
    private String code;
    private String name;
    private int sortOrder;
    private boolean enabled;
}
