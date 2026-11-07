package com.ginkgo.foodmap.user.entity;

import lombok.Data;

/** 关注事务锁定时使用的最小账号状态，不跨模块暴露。 */
@Data
public class FollowUserEntity {
    private Long id;
    private String status;
    private boolean emailVerified;
}
