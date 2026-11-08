package com.ginkgo.foodmap.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

import java.util.UUID;

/** 只允许全体公告内容及客户端请求号；发布身份与时间不接受客户端指定。 */
@Data
public class AnnouncementPublishRequest {
    @NotNull private UUID clientRequestId;
    @NotBlank private String title;
    @NotBlank private String content;
}
