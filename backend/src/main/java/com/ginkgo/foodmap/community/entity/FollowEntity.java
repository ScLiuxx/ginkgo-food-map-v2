package com.ginkgo.foodmap.community.entity;

import lombok.Data;

/** 同一有向关系永久保留，取消不丢失首次关系身份。 */
@Data
public class FollowEntity {
    private Long id;
    private Long followerUserId;
    private Long followedUserId;
    private boolean active;
}
