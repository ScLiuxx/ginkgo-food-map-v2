package com.ginkgo.foodmap.restaurant.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 完整商户业务资料申请，沿用基础资料约束；身份、统计、审核结果均不可由客户端指定。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RestaurantChangeRequest extends RestaurantSaveRequest {
    @NotNull
    private UUID clientRequestId;
    @NotNull
    @Pattern(regexp = "REGULAR|CANTEEN")
    private String restaurantType = "REGULAR";
    private Boolean supportsDineIn;
    private Boolean supportsDelivery;
    /** 仅接收已上传的本人商户用途图片ID，最多五张；空列表不入摘要，兼容旧文字申请重试。 */
    @NotNull
    @Size(max = 5)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String> photoIds = new ArrayList<>();
}
