package com.foodmap.restaurant.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 分类字典的内部读取实体；停用只影响后续写入，历史查询继续保留分类名称。 */
@Data
@TableName("restaurant_categories")
public class RestaurantCategoryEntity {
    private String code;
    private String name;
    private int sortOrder;
    private boolean enabled;
}
