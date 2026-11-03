package com.ginkgo.foodmap.restaurant.entity;

import lombok.Data;
import java.time.Instant;

/** restaurant模块内部相册记录；文件ID和来源申请不直接作为公开响应。 */
@Data
public class RestaurantPhotoEntity {
    private Long id;
    private Long restaurantId;
    private Long mediaFileId;
    private int position;
    private Long sourceRequestId;
    private Instant createdAt;
}
