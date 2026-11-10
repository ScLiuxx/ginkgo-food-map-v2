package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

/** 关闭连接时完全不注册客户端；配置错误在尝试连接之前失败。 */
class RestaurantSearchConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(RestaurantSearchConfiguration.class);

    /** 防止正数时长转换成毫秒后归零，意外关闭底层超时。 */
    @Test
    void enabledRejectsSubMillisecondResponseTimeout() {
        context.withPropertyValues(
                        "app.search.elasticsearch.enabled=true",
                        "app.search.elasticsearch.socket-timeout=1ns")
                .run(
                        application -> {
                            assertThat(application).hasFailed();
                            assertThat(application.getStartupFailure())
                                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                                    .hasStackTraceContaining("至少为1毫秒");
                        });
    }

    @Test
    void defaultDoesNotCreateClients() {
        context.run(
                application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application).doesNotHaveBean(ElasticsearchClient.class);
                    assertThat(application).doesNotHaveBean(ElasticsearchOperations.class);
                });
    }

    @Test
    void explicitlyDisabledDoesNotRequireReachableServer() {
        context.withPropertyValues(
                        "app.search.elasticsearch.enabled=false",
                        "app.search.elasticsearch.address=127.0.0.1:1")
                .run(
                        application -> {
                            assertThat(application).hasNotFailed();
                            assertThat(application).doesNotHaveBean(ElasticsearchClient.class);
                        });
    }

    @Test
    void enabledRejectsInfiniteTimeoutBeforeConnecting() {
        context.withPropertyValues(
                        "app.search.elasticsearch.enabled=true",
                        "app.search.elasticsearch.socket-timeout=0s")
                .run(
                        application -> {
                            assertThat(application).hasFailed();
                            assertThat(application.getStartupFailure())
                                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                                    .hasStackTraceContaining("ES连接和响应超时必须大于0");
                        });
    }
}
