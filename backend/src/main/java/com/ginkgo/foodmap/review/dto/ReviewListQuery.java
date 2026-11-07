package com.ginkgo.foodmap.review.dto;

import jakarta.validation.constraints.*;

import lombok.Data;

/** 作者组分页参数；页码有界，每页最多50组，偏移量使用long避免乘法溢出。 */
@Data
public class ReviewListQuery {
    /** DEFAULT按点赞数及作者等级，TIME按发布时间；拒绝未知排序。 */
    @NotNull
    @Pattern(regexp = "DEFAULT|TIME")
    private String sort = "DEFAULT";

    @NotNull
    @Min(1)
    private Integer page = 1;

    @NotNull
    @Min(1)
    @Max(50)
    private Integer pageSize = 8;

    /** 由已校验的公开参数计算，不允许HTTP直接绑定。 */
    public long getOffset() {
        return ((long) page - 1) * pageSize;
    }
}
