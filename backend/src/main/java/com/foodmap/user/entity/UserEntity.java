package com.foodmap.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.foodmap.user.domain.StudentVerificationStatus;
import com.foodmap.user.domain.UserStatus;
import lombok.Data;
import lombok.ToString;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code users} 表的 MyBatis-Plus 持久化实体。
 *
 * <p>Entity 只用于 user 模块内部的数据库读写，Controller 不得直接接收或返回该对象。
 * 该对象只接收 BCrypt 哈希，不接收明文密码。Lombok 统一生成访问器和构造器，密码哈希字段显式
 * 排除在 {@code toString()} 之外，避免日志意外泄露。</p>
 */
@Data
@TableName("users")
public class UserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String email;

    @TableField("password_hash")
    @ToString.Exclude
    private String passwordHash;

    @TableField("email_verified")
    private boolean emailVerified;

    @TableField("email_verified_at")
    private Instant emailVerifiedAt;

    private UserStatus status;

    @TableField("student_verification_status")
    private StudentVerificationStatus studentVerificationStatus;

    @TableField("credential_version")
    private long credentialVersion;

    /** MyBatis-Plus 乐观锁版本，必须与数据库 {@code row_version} 列保持一致。 */
    @Version
    @TableField("row_version")
    private long rowVersion;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    /**
     * 创建已经通过校园邮箱验证码的待持久化用户。
     *
     * @param username     已通过格式校验的用户名
     * @param email        已通过验证码校验的校园邮箱
     * @param passwordHash BCrypt 密码哈希，禁止传入明文密码
     * @return 待插入数据库的用户实体
     */
    public static UserEntity registerVerified(String username, String email, String passwordHash) {
        UserEntity user = new UserEntity();
        user.username = requireText(username, "username").trim();
        user.email = requireText(email, "email").trim().toLowerCase(Locale.ROOT);
        user.passwordHash = requireText(passwordHash, "passwordHash");
        user.emailVerified = true;
        user.emailVerifiedAt = Instant.now();
        user.status = UserStatus.ACTIVE;
        user.studentVerificationStatus = StudentVerificationStatus.PENDING;
        user.credentialVersion = 0;
        user.rowVersion = 0;
        return user;
    }

    /**
     * 替换密码哈希并递增凭证版本，使旧版本 JWT 无法继续通过认证。
     *
     * @param newPasswordHash 新的 BCrypt 密码哈希
     */
    public void changePasswordHash(String newPasswordHash) {
        this.passwordHash = requireText(newPasswordHash, "newPasswordHash");
        this.credentialVersion = Math.incrementExact(this.credentialVersion);
    }

    /**
     * 判断持久化账号状态是否允许登录；Redis 临时限流仍需认证服务额外判断。
     *
     * @return ACTIVE 状态返回 {@code true}
     */
    public boolean isLoginAllowed() {
        return status == UserStatus.ACTIVE;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
