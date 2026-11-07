package com.ginkgo.foodmap.user.vo;

import lombok.Data;

/** 登录用户可见的主页白名单；不包含邮箱、经验余额、账号处罚或申请记录。 */
@Data
public class PublicProfileVo {
    private String userId;
    private String username;
    private String bio;
    private int level;
    private long receivedLikes;
}
