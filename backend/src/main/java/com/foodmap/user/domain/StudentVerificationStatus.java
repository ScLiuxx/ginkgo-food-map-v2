package com.foodmap.user.domain;

/**
 * 学生身份的扩展认证状态。
 *
 * <p>校园邮箱验证只证明用户能够接收该邮箱邮件，不等同于完成更严格的学生身份审核，
 * 因此该状态与 {@code emailVerified} 分开保存。</p>
 */
public enum StudentVerificationStatus {
    /** 尚未完成扩展学生身份认证。 */
    PENDING,
    /** 扩展学生身份认证已通过。 */
    VERIFIED,
    /** 扩展学生身份认证被拒绝。 */
    REJECTED
}
