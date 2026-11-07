package com.ginkgo.foodmap.community.dto;

import jakarta.validation.constraints.*;

import lombok.Data;

/** 名单按关系ID倒序分页，最多50项，不接受调用方指定offset。 */
@Data
public class FollowListQuery {
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
