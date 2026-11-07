package com.ginkgo.foodmap.user.vo;

import lombok.Data;

/** 本人资料响应白名单；不包含完整邮箱、密码、凭证版本、角色或处罚信息。 */
@Data
public class UserProfileVo {
    private String userId;
    private String username;
    private String maskedEmail;
    private String bio;
}
