package com.foodmap.security.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 认证模块向业务服务提供的当前操作者，完全来自已验证安全上下文，不接受HTTP参数构造。 */
@Data
@AllArgsConstructor
public class AuthenticatedActor {
    private long userId;
    private boolean administrator;
}
