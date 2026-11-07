package com.ginkgo.foodmap.user.vo;

import lombok.Data;

/** 本人关注和粉丝名单可见性，两者独立保存。 */
@Data
public class FollowPrivacyVo {
    private String userId;
    private boolean followingPublic;
    private boolean followersPublic;
}
