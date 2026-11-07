package com.ginkgo.foodmap.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ginkgo.foodmap.user.entity.RoleEntity;
import com.ginkgo.foodmap.user.entity.UserEntity;
import com.ginkgo.foodmap.user.entity.UserRoleEntity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * MyBatis-Plus 用户持久化层的快速回归测试。
 *
 * <p>本测试使用 H2 验证对象映射、Mapper 行为和乐观锁插件。真实 MySQL 排序规则及并发约束 仍由 Flyway/MySQL 集成测试负责，不能用本测试结果替代。
 */
@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:user_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:sql/user-persistence-schema.sql"
        })
@Transactional
@org.springframework.context.annotation.Import(com.ginkgo.foodmap.security.TestJwtKeys.class)
class UserMapperTest {

    private static final String PASSWORD_HASH =
            "$2a$10$testOnlyHashValueNotUsedForAuthentication000000000000000";

    @Autowired private UserMapper userMapper;

    @Autowired private RoleMapper roleMapper;

    @Autowired private UserRoleMapper userRoleMapper;

    /** 验证插入时回填自增 ID，并能按规范化用户名和邮箱查询。 */
    @Test
    void shouldInsertAndFindVerifiedUser() {
        UserEntity user =
                UserEntity.registerVerified(
                        "ginkgo_user", "Student@std.uestc.edu.cn", PASSWORD_HASH);

        assertThat(userMapper.insert(user)).isEqualTo(1);
        assertThat(user.getId()).isNotNull();
        assertThat(userMapper.selectByUsername("ginkgo_user")).isPresent();
        assertThat(userMapper.selectByEmail("student@std.uestc.edu.cn"))
                .get()
                .extracting(UserEntity::isEmailVerified)
                .isEqualTo(true);
    }

    /** 验证数据库唯一约束会拒绝并发场景下可能出现的重复用户名。 */
    @Test
    void shouldRejectDuplicateUsername() {
        userMapper.insert(
                UserEntity.registerVerified(
                        "duplicate_user", "first@std.uestc.edu.cn", PASSWORD_HASH));

        assertThatThrownBy(
                        () ->
                                userMapper.insert(
                                        UserEntity.registerVerified(
                                                "duplicate_user",
                                                "second@std.uestc.edu.cn",
                                                PASSWORD_HASH)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 验证邮箱规范化与数据库唯一约束共同阻止同一邮箱重复注册。 */
    @Test
    void shouldRejectDuplicateEmail() {
        userMapper.insert(
                UserEntity.registerVerified("first_user", "same@std.uestc.edu.cn", PASSWORD_HASH));

        assertThatThrownBy(
                        () ->
                                userMapper.insert(
                                        UserEntity.registerVerified(
                                                "second_user",
                                                "SAME@std.uestc.edu.cn",
                                                PASSWORD_HASH)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 验证密码变更递增凭证版本，且乐观锁拒绝使用旧 row_version 的并发更新。 */
    @Test
    void shouldRejectStalePasswordUpdateWithOptimisticLock() {
        UserEntity inserted =
                UserEntity.registerVerified(
                        "password_user", "password@std.uestc.edu.cn", PASSWORD_HASH);
        userMapper.insert(inserted);

        UserEntity firstTransaction = userMapper.selectById(inserted.getId());
        UserEntity staleTransaction = userMapper.selectById(inserted.getId());

        firstTransaction.changePasswordHash(PASSWORD_HASH + "-first");
        assertThat(userMapper.updateById(firstTransaction)).isEqualTo(1);
        assertThat(firstTransaction.getCredentialVersion()).isEqualTo(1);

        staleTransaction.changePasswordHash(PASSWORD_HASH + "-stale");
        assertThat(userMapper.updateById(staleTransaction)).isZero();
    }

    /** 自定义密码更新同时递增两个版本，陈旧版本或错误邮箱不能覆盖已提交的密码。 */
    @Test
    void shouldAtomicallyResetPasswordAndRejectStaleSnapshot() {
        UserEntity user =
                UserEntity.registerVerified("reset_user", "reset@std.uestc.edu.cn", PASSWORD_HASH);
        userMapper.insert(user);
        assertThat(
                        userMapper.resetPasswordIfCurrent(
                                user.getId(), user.getEmail(), 0, 0, "new-hash"))
                .isEqualTo(1);
        UserEntity changed = userMapper.selectById(user.getId());
        assertThat(changed.getPasswordHash()).isEqualTo("new-hash");
        assertThat(changed.getCredentialVersion()).isEqualTo(1L);
        assertThat(changed.getRowVersion()).isEqualTo(1L);
        assertThat(userMapper.resetPasswordIfCurrent(user.getId(), user.getEmail(), 0, 0, "stale"))
                .isZero();
        assertThat(userMapper.resetPasswordIfCurrent(user.getId(), user.getEmail(), 1, 0, "stale"))
                .isZero();
        assertThat(userMapper.resetPasswordIfCurrent(user.getId(), user.getEmail(), 0, 1, "stale"))
                .isZero();
        assertThat(
                        userMapper.resetPasswordIfCurrent(
                                user.getId(), "other@std.uestc.edu.cn", 1, 1, "wrong"))
                .isZero();
        assertThat(userMapper.selectById(user.getId()).getPasswordHash()).isEqualTo("new-hash");
    }

    /** 哈希计算期间账号被停用或取消邮箱验证，即使两个版本未变化也不能完成密码更新。 */
    @Test
    void shouldRejectPasswordUpdateForUnavailableAccount() {
        UserEntity user =
                UserEntity.registerVerified(
                        "reset_disabled", "disabled@std.uestc.edu.cn", PASSWORD_HASH);
        user.setStatus(com.ginkgo.foodmap.user.domain.UserStatus.DISABLED);
        userMapper.insert(user);
        assertThat(
                        userMapper.resetPasswordIfCurrent(
                                user.getId(), user.getEmail(), 0, 0, "new-hash"))
                .isZero();
        user.setStatus(com.ginkgo.foodmap.user.domain.UserStatus.ACTIVE);
        user.setEmailVerified(false);
        userMapper.updateById(user);
        assertThat(
                        userMapper.resetPasswordIfCurrent(
                                user.getId(), user.getEmail(), user.getRowVersion(), 0, "new-hash"))
                .isZero();
        assertThat(userMapper.selectById(user.getId()).getPasswordHash()).isEqualTo(PASSWORD_HASH);
    }

    /** 验证复合主键关系表使用显式 SQL 完成角色分配和查询。 */
    @Test
    void shouldInsertAndFindExplicitUserRole() {
        UserEntity user =
                UserEntity.registerVerified("role_user", "role@std.uestc.edu.cn", PASSWORD_HASH);
        RoleEntity role = RoleEntity.create("ROLE_TEST", "测试角色");
        userMapper.insert(user);
        roleMapper.insert(role);

        assertThat(userRoleMapper.insert(UserRoleEntity.assign(user.getId(), role.getId())))
                .isEqualTo(1);
        assertThat(userRoleMapper.selectByUserId(user.getId()))
                .singleElement()
                .satisfies(relation -> assertThat(relation.getRoleId()).isEqualTo(role.getId()));
    }
}
