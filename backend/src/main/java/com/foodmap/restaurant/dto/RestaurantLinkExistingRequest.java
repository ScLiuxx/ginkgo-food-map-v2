package com.foodmap.restaurant.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import lombok.Data;

/** 管理员明确选择已收录店铺；不接收资料覆盖、图片发布或点评作者字段。 */
@Data
public class RestaurantLinkExistingRequest {
    @NotNull
    @Pattern(regexp = "[1-9][0-9]{0,18}")
    private String restaurantId;
}
