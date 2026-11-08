package com.ginkgo.foodmap.notification.dto;

import jakarta.validation.constraints.*;

import lombok.Data;

/** 已开放首页、审核系统通知及评论分类；关注分类已接入，不允许任意分类进入SQL。 */
@Data
public class NotificationQuery {
    @NotNull
    @Pattern(regexp = "ALL|SYSTEM|COMMENTS|LIKES|FOLLOWERS")
    private String category = "ALL";

    @Min(1)
    @Max(1000000)
    private int page = 1;

    @Min(1)
    @Max(50)
    private int pageSize = 20;
}
