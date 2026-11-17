package com.foodmap.user.dto;

import com.foodmap.user.domain.VerificationCodePurpose;
import lombok.Data;
import lombok.ToString;

/**
 * 已通过 Redis 限流、等待邮件投递的验证码预留结果。
 *
 * <p>使用 Lombok {@link Data} 生成访问器、构造器、equals 和 hashCode，不手写 Getter、Setter。
 * 字段保持 final，避免邮件流程中途修改；{@link ToString} 只输出用途，禁止泄露验证码和邮箱。</p>
 */
@Data
@ToString(onlyExplicitlyIncluded = true)
public final class VerificationCodeReservation {

    @ToString.Include
    private final VerificationCodePurpose purpose;

    private final String recipientEmail;

    private final String code;

    private final String reservationId;
}
