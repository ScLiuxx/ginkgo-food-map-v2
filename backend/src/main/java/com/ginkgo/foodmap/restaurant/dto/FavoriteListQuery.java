package com.ginkgo.foodmap.restaurant.dto;

import jakarta.validation.constraints.*;

import lombok.Data;

/** 收藏按关系时间分页，偏移使用long，最多50项。 */
@Data
public class FavoriteListQuery {
    @NotNull
    @Min(1)
    private Integer page = 1;

    @NotNull
    @Min(1)
    @Max(50)
    private Integer pageSize = 8;

    public long getOffset() {
        return ((long) page - 1) * pageSize;
    }
}
