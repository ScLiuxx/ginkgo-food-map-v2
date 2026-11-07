package com.ginkgo.foodmap.restaurant.dto;

import lombok.Data;

/** 面向点评模块的可见店铺白名单，不含删除元数据、作者或管理权限。 */
@Data
public class RestaurantReviewTarget {
    private String id;
    private String name;
    private String businessStatus;
}
