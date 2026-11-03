package com.ginkgo.foodmap.restaurant.entity;

import lombok.Data;

/** 由正式商户/申请/相册关系解析的内部引用；交给file服务校验，不跨模块返回Entity。 */
@Data
public class RestaurantPhotoFileEntity {
    private Long mediaFileId;
    private Long submittedBy;
}
