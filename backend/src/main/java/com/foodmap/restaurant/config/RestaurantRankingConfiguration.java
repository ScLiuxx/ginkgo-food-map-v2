package com.foodmap.restaurant.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import com.foodmap.restaurant.cache.RestaurantRankingRedisStore;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;

import java.time.Clock;

/** 装配榜单专用Redis连接与时间依赖；生成任务独立管理线程，不改变全局调度装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RestaurantRankingProperties.class)
public class RestaurantRankingConfiguration {
    /** 独立时钟便于确定性验证24小时边界，不依赖机器默认时区。 */
    @Bean
    Clock restaurantRankingClock() {
        return Clock.systemUTC();
    }

    /** 复用现有服务器/认证/DB配置，但隔离榜单200ms超时及连接生命周期。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.ranking.redis-enabled", havingValue = "true", matchIfMissing = true)
    RestaurantRankingRedisStore restaurantRankingRedisStore(
            RedisConnectionFactory shared, RestaurantRankingProperties properties) {
        if (!(shared instanceof LettuceConnectionFactory lettuce) || lettuce.getStandaloneConfiguration() == null) {
            throw new IllegalStateException("Ranking cache currently requires standalone Lettuce Redis");
        }
        var options = ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(properties.getRedisConnectTimeout()).build())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(32).build();
        var client = LettuceClientConfiguration.builder()
                .commandTimeout(properties.getRedisCommandTimeout()).clientOptions(options);
        // 复用Boot的Netty线程资源，仍保留独立连接和超时，避免为四个小榜单额外扩张线程池。
        lettuce.getClientConfiguration().getClientResources().ifPresent(client::clientResources);
        if (lettuce.getClientConfiguration().isUseSsl()) {
            client.useSsl();
        }
        return new RestaurantRankingRedisStore(new LettuceConnectionFactory(lettuce.getStandaloneConfiguration(), client.build()));
    }
}
