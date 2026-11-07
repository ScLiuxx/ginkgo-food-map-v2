package com.ginkgo.foodmap.user.entity;

import lombok.Data;
import lombok.ToString;

/** user模块内部的最小资料投影；不加载密码或凭证，邮箱不能直接序列化给前端。 */
@Data
public class UserProfileEntity {
    private long id;
    private String username;
    @ToString.Exclude private String email;
    @ToString.Exclude private String bio;
}
