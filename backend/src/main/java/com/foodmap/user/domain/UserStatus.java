package com.foodmap.user.domain;

/**
 * 用户账号的持久化状态。
 *
 * <p>登录时只有 {@link #ACTIVE} 可以通过账号状态校验。临时的密码错误限流保存在 Redis，
 * 不应频繁修改这里的持久化状态；{@link #LOCKED} 用于需要人工处理的安全锁定。</p>
 */
public enum UserStatus {
    /** 账号状态正常，允许继续进行身份认证。 */
    ACTIVE,
    /** 因安全事件被持久化锁定，需要管理员或安全流程解除。 */
    LOCKED,
    /** 账号被停用，不允许登录。 */
    DISABLED,
    /** 逻辑删除状态，保留必要关系和审计信息。 */
    DELETED
}
