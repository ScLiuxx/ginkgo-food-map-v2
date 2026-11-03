package com.ginkgo.foodmap.restaurant.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 审核完整业务资料包含餐厅类型与三态用餐方式；独立DTO避免改变原投稿序列化及幂等摘要。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RestaurantApproveRequest extends RestaurantSaveRequest {
    @NotNull
    @Pattern(regexp = "REGULAR|CANTEEN")
    private String restaurantType = "REGULAR";
    /** null表示未知，false表示明确不支持，不能互相替代。 */
    private Boolean supportsDineIn;
    private Boolean supportsDelivery;
}
