package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 使用真实TCP连接模拟服务接收连接却不响应，无需Docker或外部ES即可回归响应等待上限。 */
class RestaurantSearchResponseTimeoutTest {
    @Test
    void establishedConnectionWithoutResponseTimesOut() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var server = new ServerSocket(0);
                var executor = Executors.newSingleThreadExecutor();
                var application = new AnnotationConfigApplicationContext()) {
            // 即使客户端初始化失败，也保证accept不会阻塞测试执行器退出。
            server.setSoTimeout(5000);
            var peer =
                    executor.submit(
                            () -> {
                                try (var socket = server.accept()) {
                                    accepted.countDown();
                                    // 保持连接不发送响应，结束时由finally释放，不用长时间sleep拖慢测试。
                                    release.await(10, TimeUnit.SECONDS);
                                }
                                return null;
                            });
            try {
                TestPropertyValues.of(
                                "app.search.elasticsearch.enabled=true",
                                "app.search.elasticsearch.address=127.0.0.1:"
                                        + server.getLocalPort(),
                                "app.search.elasticsearch.connect-timeout=1s",
                                "app.search.elasticsearch.socket-timeout=250ms")
                        .applyTo(application);
                application.register(RestaurantSearchConfiguration.class);
                application.refresh();
                var client = application.getBean(ElasticsearchClient.class);
                // 3秒是宽松调度容差；未设置请求级超时的实现会在此失败，
                // 异常必须来自真实读取超时，不能仅验证配置对象的字段值。
                assertTimeoutPreemptively(
                        Duration.ofSeconds(3),
                        () ->
                                assertThatThrownBy(client::info)
                                        .isInstanceOf(IOException.class)
                                        .hasStackTraceContaining("Timeout"));
                assertThat(accepted.await(1, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            peer.get(2, TimeUnit.SECONDS);
        }
    }
}
