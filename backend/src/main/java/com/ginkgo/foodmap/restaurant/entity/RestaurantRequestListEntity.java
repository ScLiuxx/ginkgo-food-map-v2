package com.ginkgo.foodmap.restaurant.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 申请列表只取必要字段与图片计数，不在分页查询中加载整份JSON快照。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RestaurantRequestListEntity extends RestaurantChangeEntity {
    private String requestName;
    private int photoCount;
}
