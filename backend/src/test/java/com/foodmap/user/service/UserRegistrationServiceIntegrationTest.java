package com.foodmap.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.foodmap.user.dto.RegisterRequest;
import com.foodmap.user.entity.RoleEntity;
import com.foodmap.user.entity.UserEntity;
import com.foodmap.user.entity.UserRoleEntity;
import com.foodmap.user.mapper.RoleMapper;
import com.foodmap.user.mapper.UserMapper;
import com.foodmap.user.mapper.UserRoleMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 注册流程数据库集成测试。
 *
 * <p>测试使用真实 MyBatis Mapper、事务代理和 BCrypt，只替换 Redis 验证码服务与角色关系 Mapper。
 * 这样既能检查实际持久化结果，也能稳定制造“用户写入后角色分配失败”的场景，证明事务会回滚。
 */
@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:user_registration;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:sql/user-persistence-schema.sql"
        })
@org.springframework.context.annotation.Import(com.foodmap.security.TestJwtKeys.class)
class UserRegistrationServiceIntegrationTest {

    @Autowired private UserRegistrationService service;

    @Autowired private UserMapper userMapper;

    @Autowired private RoleMapper roleMapper;

    @Autowired private PasswordEncoder passwordEncoder;

    /** Redis 原子消费已经由独立真实 Redis 测试覆盖，本测试只关注数据库事务。 */
    @MockitoBean private VerificationCodeService verificationCodeService;

    /** 使用可控返回值制造角色分配成功或失败，不在本测试重复验证该 Mapper 的 SQL。 */
    @MockitoBean private UserRoleMapper userRoleMapper;

    @BeforeEach
    void resetDatabase() {
        userMapper.delete(null);
        roleMapper.delete(null);
        roleMapper.insert(RoleEntity.create("ROLE_USER", "普通用户"));
    }

    /** 正常注册会保存 BCrypt 哈希，数据库中绝不出现明文密码。 */
    @Test
    void shouldPersistBcryptPasswordHash() {
        when(userRoleMapper.insert(any(UserRoleEntity.class))).thenReturn(1);

        service.register(request("student@std.uestc.edu.cn", "中"));

        UserEntity saved = userMapper.selectByEmail("student@std.uestc.edu.cn").orElseThrow();
        assertThat(saved.getUsername()).isEqualTo("中");
        assertThat(saved.getPasswordHash()).isNotEqualTo("Foodmap2026");
        assertThat(passwordEncoder.matches("Foodmap2026", saved.getPasswordHash())).isTrue();
        assertThat(saved.isEmailVerified()).isTrue();
    }

    /** 角色分配失败发生在用户插入之后，整个数据库事务仍必须回滚。 */
    @Test
    void shouldRollbackUserWhenRoleAssignmentFails() {
        when(userRoleMapper.insert(any(UserRoleEntity.class))).thenReturn(0);

        assertThatThrownBy(
                        () ->
                                service.register(
                                        request("rollback@std.uestc.edu.cn", "rollback_user")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(userMapper.selectByEmail("rollback@std.uestc.edu.cn")).isEmpty();
    }

    private static RegisterRequest request(String email, String username) {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(email);
        request.setVerificationCode("123456");
        request.setUsername(username);
        request.setPassword("Foodmap2026");
        return request;
    }
}
