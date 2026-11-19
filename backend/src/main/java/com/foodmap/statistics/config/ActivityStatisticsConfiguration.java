package com.foodmap.statistics.config;

import com.foodmap.statistics.storage.ActivityRedisStore;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

/** 统计连接复用现有服务器、凭证、逻辑库和Netty资源，独立限制超时，不影响认证连接。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ActivityStatisticsProperties.class)
public class ActivityStatisticsConfiguration {
    @Bean(destroyMethod = "close")
    ActivityRedisStore activityRedisStore(RedisConnectionFactory shared) {
        if (!(shared instanceof LettuceConnectionFactory lettuce)
                || lettuce.getStandaloneConfiguration() == null) {
            throw new IllegalStateException(
                    "Activity statistics requires standalone Lettuce Redis");
        }
        var timeout = Duration.ofMillis(200);
        var options =
                ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .requestQueueSize(32)
                        .build();
        var client =
                LettuceClientConfiguration.builder().commandTimeout(timeout).clientOptions(options);
        lettuce.getClientConfiguration().getClientResources().ifPresent(client::clientResources);
        if (lettuce.getClientConfiguration().isUseSsl()) {
            client.useSsl();
        }
        return new ActivityRedisStore(
                new LettuceConnectionFactory(lettuce.getStandaloneConfiguration(), client.build()));
    }
}
