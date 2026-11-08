package com.ginkgo.foodmap.notification.entity;

import lombok.Data;

import java.time.Instant;

/** 公告持久化记录，发布请求摘要仅用于服务端幂等校验，不直接作为API响应。 */
@Data
public class AnnouncementEntity {
    private Long id;
    private byte[] clientRequestId;
    private byte[] requestHash;
    private String title;
    private String content;
    private String status;
    private Long publishedBy;
    private Instant publishedAt;
    private Long withdrawnBy;
    private Instant withdrawnAt;
}
