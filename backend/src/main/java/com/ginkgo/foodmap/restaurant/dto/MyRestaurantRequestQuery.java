package com.ginkgo.foodmap.restaurant.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** 本人申请只开放状态与有界分页；账号、排序及offset不能由客户端选择。 */
@Data
public class MyRestaurantRequestQuery {
    @NotNull @Pattern(regexp = "ALL|PENDING|APPROVED|REJECTED|WITHDRAWN")
    private String status = "ALL";
    @Min(1) @Max(1000000)
    private int page = 1;
    @Min(1) @Max(50)
    private int pageSize = 20;
}
