package com.ginkgo.foodmap.restaurant.cache;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collection;

/** 榜单专用Redis连接及String操作；独立超时不改变登录会话、验证码或通用缓存配置。 */
public class RestaurantRankingRedisStore implements AutoCloseable {
    private final LettuceConnectionFactory connections;
    private final StringRedisTemplate redis;

    public RestaurantRankingRedisStore(LettuceConnectionFactory connections) {
        this.connections = connections;
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
    }

    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    /** SET与PX一次完成，不用独立EXPIRE留下无有效期的键。 */
    public void set(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    /** 只删除明确拥有的最多四个键，从不使用KEYS、SCAN或FLUSHDB。 */
    public void delete(Collection<String> keys) {
        redis.delete(keys);
    }

    /** Spring关闭时释放榜单自己的连接，不关闭共享认证连接工厂。 */
    @Override
    public void close() {
        connections.destroy();
    }
}
