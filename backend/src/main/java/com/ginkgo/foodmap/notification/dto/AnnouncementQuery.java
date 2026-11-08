package com.ginkgo.foodmap.notification.dto;

import jakarta.validation.constraints.*;

import lombok.Data;

/** 管理列表采用有界分页，只允许明确的公告状态。 */
@Data
public class AnnouncementQuery {
    @NotNull
    @Pattern(regexp = "ALL|PUBLISHED|WITHDRAWN")
    private String status = "ALL";

    @Min(1)
    @Max(1000000)
    private int page = 1;

    @Min(1)
    @Max(50)
    private int pageSize = 20;
}
