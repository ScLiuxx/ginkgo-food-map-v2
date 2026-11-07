package com.ginkgo.foodmap.review.dto;

import jakarta.validation.constraints.*;

import lombok.Data;

/** 单次最多50条，限制响应大小和公开作者批量查询规模。 */
@Data
public class ReplyListQuery {
    @NotNull
    @Min(1)
    @Max(50)
    private Integer pageSize = 10;

    @Size(max = 200)
    private String cursor;
}
