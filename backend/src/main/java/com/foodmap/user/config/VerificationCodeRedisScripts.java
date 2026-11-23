package com.foodmap.user.config;

import lombok.Data;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 一次性注入验证码服务的 Redis Lua 脚本集合。
 *
 * <p>使用 Lombok {@link Data} 生成构造器和访问器，使 Service 可以保持标准的
 * {@code @RequiredArgsConstructor} 构造器注入，不需要为四个脚本手写带 Qualifier 的构造器。</p>
 */
@Data
public class VerificationCodeRedisScripts {

    private final RedisScript<Long> reserve;
    private final RedisScript<Long> activate;
    private final RedisScript<Long> cancel;
    private final RedisScript<Long> consume;
}
