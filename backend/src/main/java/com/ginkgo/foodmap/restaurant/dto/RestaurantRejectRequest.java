package com.ginkgo.foodmap.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 驳回只接收原因；审核身份、时间和终态不得由客户端指定。 */
@Data
public class RestaurantRejectRequest {
    @NotBlank
    @Size(max = 200)
    private String reason;

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("驳回请求包含不支持的字段");
    }
}
