package com.ginkgo.foodmap.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 本人修改待审申请的完整业务内容。
 *
 * <p>修改是整体替换，不接收 clientRequestId：幂等边界由申请ID和本次完整内容确定，
 * 客户端不能借修改改写提交回执或切换申请人。图片同样整体替换，空列表表示不再附图。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RestaurantChangeEditRequest extends RestaurantSaveRequest {
    @NotNull
    @Pattern(regexp = "REGULAR|CANTEEN")
    private String restaurantType = "REGULAR";

    private Boolean supportsDineIn;

    private Boolean supportsDelivery;

    /** 仅接收已上传的本人商户用途图片ID，最多五张；替换时与当前顺序整体比较。 */
    @NotNull
    @Size(max = 5)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String> photoIds = new ArrayList<>();
}
