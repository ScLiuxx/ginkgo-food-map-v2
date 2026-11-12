package com.ginkgo.foodmap.restaurant.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 榜单只接受四个固定组合，不允许客户端扩大条数、指定内部来源或控制缓存。 */
@Data
public class RestaurantRankingQuery {
    @NotNull(message = "请选择校区")
    @Pattern(regexp = "QINGSHUIHE|SHAHE", message = "校区仅支持QINGSHUIHE或SHAHE")
    private String campusCode;

    @NotNull(message = "请选择榜单周期")
    @Pattern(regexp = "WEEK|MONTH", message = "周期仅支持WEEK或MONTH")
    private String periodType;
}
