package com.foodmap.security.vo;

import com.foodmap.user.dto.UserIdentity;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

/** 前端可展示的用户信息，不暴露密码、内部凭证版本或持久化 Entity。 */
@Data
@AllArgsConstructor
public class CurrentUser {
    private Long id;
    private String username;
    private List<String> roles;

    /** 将跨模块账号快照转换为公开响应。 */
    public static CurrentUser from(UserIdentity user) {
        return new CurrentUser(user.getId(), user.getUsername(), user.getRoles());
    }
}
