package com.ginkgo.foodmap.restaurant.config;

import lombok.RequiredArgsConstructor;

import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;
import org.springframework.data.elasticsearch.client.elc.rest5_client.Rest5Clients;

/**
 * 显式开启才注册Spring Data提供的ES客户端和ElasticsearchOperations。
 * 不启用Repository扫描，不建索引、不导入数据；查询和Outbox同步分别由独立开关控制。
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
        if (properties.getConnectTimeout() == null
                || properties.getConnectTimeout().isNegative()
                || properties.getConnectTimeout().toMillis() == 0
                || properties.getSocketTimeout() == null
                || properties.getSocketTimeout().isNegative()
                || properties.getSocketTimeout().toMillis() == 0) {
            // HTTP客户端按毫秒工作；不足1毫秒会被截断为0并变成无限等待。
            throw new IllegalArgumentException("ES连接和响应超时必须大于0且至少为1毫秒");
        }
        return ClientConfiguration.builder()
                .connectedTo(properties.getAddress())
                .withConnectTimeout(properties.getConnectTimeout())
                .withSocketTimeout(properties.getSocketTimeout())
                // Rest5请求级响应超时会覆盖连接的socket超时，必须同时设置，
                // 否则连接已建立但ES不响应时，无法按配置及时释放请求并回退SQL。
                .withClientConfigurer(
                        Rest5Clients.ElasticsearchRequestConfigCallback.from(
                                request ->
                                        request.setResponseTimeout(
                                                Timeout.ofMilliseconds(
                                                        properties.getSocketTimeout().toMillis()))))
                .build();
    }
}
