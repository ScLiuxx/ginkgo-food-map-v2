package com.foodmap.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

/** user 模块对认证模块公开的账号快照，不包含密码哈希或邮箱等私密数据。 */
@Data
@AllArgsConstructor
public class UserIdentity {
    private Long id;
    private String username;
    private long credentialVersion;
    private boolean loginAllowed;
    private List<String> roles;
}
