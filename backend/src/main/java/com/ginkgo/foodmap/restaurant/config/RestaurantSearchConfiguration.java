package com.ginkgo.foodmap.restaurant.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;

/**
 * 显式开启才注册Spring Data提供的ES客户端和ElasticsearchOperations。
 * 不启用Repository扫描，不建索引、不导入数据；连接仅用于本地接入验证。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.search.elasticsearch", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RestaurantSearchProperties.class)
@RequiredArgsConstructor
public class RestaurantSearchConfiguration extends ElasticsearchConfiguration {
    private final RestaurantSearchProperties properties;

    /** 由Spring Data统一创建及管理连接资源，避免业务请求反复创建客户端。 */
    @Override
    public ClientConfiguration clientConfiguration() {
        if (properties.getConnectTimeout() == null || properties.getConnectTimeout().isNegative()
                || properties.getConnectTimeout().isZero()
                || properties.getSocketTimeout() == null || properties.getSocketTimeout().isNegative()
                || properties.getSocketTimeout().isZero()) {
            throw new IllegalArgumentException("ES连接和响应超时必须大于0");
        }
        return ClientConfiguration.builder()
                .connectedTo(properties.getAddress())
                .withConnectTimeout(properties.getConnectTimeout())
                .withSocketTimeout(properties.getSocketTimeout())
                .build();
    }
}
