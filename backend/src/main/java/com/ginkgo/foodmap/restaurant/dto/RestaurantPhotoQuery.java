package com.ginkgo.foodmap.restaurant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 相册采用稳定图片关联ID游标，有界读取；顺序仅由服务端决定，不能注入位置或offset。 */
@Data
public class RestaurantPhotoQuery {
    @Pattern(regexp = "[1-9][0-9]{0,18}")
    private String cursor;
    @NotNull @Min(1) @Max(50)
    private Integer limit = 20;
}
