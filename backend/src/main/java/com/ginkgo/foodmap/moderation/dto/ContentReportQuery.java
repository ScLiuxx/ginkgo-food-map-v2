package com.ginkgo.foodmap.moderation.dto;

import jakarta.validation.constraints.*;

import lombok.Data;

/** 管理举报列表的有界筛选；状态只接受固定枚举，排序和分页由服务端确定。 */
@Data
public class ContentReportQuery {
    @NotNull @Pattern(regexp = "PENDING|RESOLVED|DISMISSED")
    private String status = "PENDING";

    @Min(1) @Max(1000000)
    private int page = 1;

    @Min(1) @Max(50)
    private int pageSize = 20;
}
