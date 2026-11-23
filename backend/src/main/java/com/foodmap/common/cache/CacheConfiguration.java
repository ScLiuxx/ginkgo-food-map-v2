package com.foodmap.common.cache;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Cache 的基础开关配置。
 *
 * <p>这里只启用缓存抽象，不承载用户认证会话。JWT 登录会话必须由 {@code security} 模块直接管理 Redis，
 * 因为会话撤销、原子更新和失败时拒绝放行不能依赖通用缓存的弱语义。</p>
 */
@Configuration
@EnableCaching
public class CacheConfiguration {
}
