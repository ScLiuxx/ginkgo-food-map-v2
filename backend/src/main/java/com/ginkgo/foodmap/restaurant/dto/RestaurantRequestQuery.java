package com.ginkgo.foodmap.restaurant.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** 管理申请列表的有界参数；类型与排序白名单在服务端确定，不接受任意SQL排序。 */
@Data
public class RestaurantRequestQuery {
    @NotNull @Pattern(regexp = "PENDING|APPROVED|REJECTED")
    private String status = "PENDING";
    @Size(max = 80)
    private String keyword = "";
    @Min(1) @Max(1000000)
    private int page = 1;
    @Min(1) @Max(50)
    private int pageSize = 20;
    /** 内部LIKE参数每次由服务端重建，转义用户输入中的通配符。 */
    private String pattern;
}
