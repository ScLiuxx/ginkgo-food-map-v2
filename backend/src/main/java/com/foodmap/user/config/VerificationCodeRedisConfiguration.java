package com.foodmap.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 邮箱验证码 Redis Lua 脚本及核心组件装配。
 */
@Configuration
public class VerificationCodeRedisConfiguration {

    /**
     * 将四段脚本封装成一个依赖，避免 Service 构造器出现多个同类型参数和手写 Qualifier。
     */
    @Bean
    VerificationCodeRedisScripts verificationCodeRedisScripts() {
        return new VerificationCodeRedisScripts(
                script("redis/verification-code-reserve.lua"),
                script("redis/verification-code-activate.lua"),
                script("redis/verification-code-cancel.lua"),
                script("redis/verification-code-consume.lua")
        );
    }

    private static DefaultRedisScript<Long> script(String classpathLocation) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpathLocation));
        script.setResultType(Long.class);
        return script;
    }
}
