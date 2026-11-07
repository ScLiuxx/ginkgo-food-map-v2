package com.ginkgo.foodmap.community.vo;

import lombok.Data;

import java.util.List;

/** 公开人数与受控名单分别返回，不泄露邮箱或账号权限。 */
public final class FollowViews {
    private FollowViews() {}

    @Data
    public static class Summary {
        private String userId;
        private long followingCount;
        private long followersCount;
        private boolean following;
    }

    @Data
    public static class Member {
        private String userId;
        private String username;
        private Integer level;
        private boolean following;
    }

    @Data
    public static class Page {
        private String userId;
        private String kind;
        private List<Member> records = List.of();
        private long total;
        private int page;
        private int pageSize;
        private long totalPages;
    }
}
