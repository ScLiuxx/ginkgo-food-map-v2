package com.ginkgo.foodmap.user.service;

import com.ginkgo.foodmap.user.dto.UserIdentity;
import com.ginkgo.foodmap.user.entity.UserEntity;
import com.ginkgo.foodmap.user.mapper.UserMapper;
import com.ginkgo.foodmap.user.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.Optional;
import java.util.List;
import java.util.Locale;

/** 向认证模块提供账号查询和密码校验；Mapper、Entity 和密码哈希始终留在 user 模块。 */
@Service
@RequiredArgsConstructor
public class UserAccountService {
    // 有效的 BCrypt 哈希用于不存在账号的等成本密码校验，避免直接跳过昂贵校验暴露账号存在性。
    private static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    /** 查询稳定用户 ID，登录限流使用 ID 防止大小写或数据库排序规则的别名绕过限制。 */
    public Optional<UserIdentity> findByUsername(String username) {
        return userMapper.selectByUsername(username).map(this::identity);
    }

    /** 用户名不含@；邮箱按注册规则转小写，同一账号的两种登录方式映射到相同用户ID。 */
    public Optional<UserIdentity> findByLoginAccount(String account) {
        return loginCandidate(account).map(this::identity);
    }

    /** 只在用户模块内按账号类型查询，避免跨模块暴露实体或密码哈希。 */
    private Optional<UserEntity> loginCandidate(String account) {
        String value = account.trim();
        return value.contains("@")
                ? userMapper.selectByEmail(value.toLowerCase(Locale.ROOT))
                : userMapper.selectByUsername(value);
    }

    /** 每次受保护请求重读账号状态、凭证版本和角色，使停用、改密或角色调整及时生效。 */
    public Optional<UserIdentity> findById(Long id) {
        return Optional.ofNullable(userMapper.selectById(id)).map(this::identity);
    }

    /** 不存在、密码错误、未验证邮箱或停用账号均返回空，由认证模块提供统一失败提示。 */
    public Optional<UserIdentity> authenticate(String account, String password) {
        Optional<UserEntity> candidate = loginCandidate(account);
        boolean matches = passwordEncoder.matches(password,
                candidate.map(UserEntity::getPasswordHash).orElse(DUMMY_HASH));
        return candidate.filter(user -> matches && user.isLoginAllowed() && user.isEmailVerified())
                .map(this::identity);
    }

    private UserIdentity identity(UserEntity user) {
        return new UserIdentity(user.getId(), user.getUsername(), user.getCredentialVersion(),
                user.isLoginAllowed() && user.isEmailVerified(),
                List.copyOf(userRoleMapper.selectRoleCodes(user.getId())));
    }
}
