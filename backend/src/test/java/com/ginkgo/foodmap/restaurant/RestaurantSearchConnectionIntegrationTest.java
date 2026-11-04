package com.ginkgo.foodmap.restaurant;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.ginkgo.foodmap.restaurant.config.RestaurantSearchConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Query;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 显式指定-Dginkgo.es.it=true才执行的本地只读验证，不依赖MySQL/Redis或完整应用启动。
 * 要求已有IK练习索引及4条虚拟商户；不创建、删除或重写任何索引。
 */
@EnabledIfSystemProperty(named = "ginkgo.es.it", matches = "true")
class RestaurantSearchConnectionIntegrationTest {
    @Test
    void connectsAndReadsExistingLabThroughSpringData() throws Exception {
        try (var application = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("app.search.elasticsearch.enabled=true",
                    "app.search.elasticsearch.address=" + System.getProperty("ginkgo.es.address", "127.0.0.1:9200"))
                    .applyTo(application);
            application.register(RestaurantSearchConfiguration.class);
            application.refresh();

            var client = application.getBean(ElasticsearchClient.class);
            assertThat(client.ping().value()).isTrue();
            assertThat(client.info().version().number()).startsWith("9.");

            var operations = application.getBean(ElasticsearchOperations.class);
            long count = operations.count(Query.findAll(), IndexCoordinates.of("ginkgo-search-lab-ik-20261103"));
            assertThat(count).isEqualTo(4);
        }
    }
}
