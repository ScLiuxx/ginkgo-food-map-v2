package com.ginkgo.foodmap.restaurant;

import static org.mockito.Mockito.*;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchSyncConfiguration;
import com.ginkgo.foodmap.restaurant.job.RestaurantSearchSyncJob;
import com.ginkgo.foodmap.restaurant.job.RestaurantSearchSyncTrigger;
import com.ginkgo.foodmap.restaurant.service.RestaurantSearchSyncService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** 验证真正的定时注册与关闭，不依赖OSS模块是否启用调度。 */
class RestaurantSearchSyncConfigurationTest {
    @Test
    void schedulesWhenEnabledWithoutOssConfiguration() {
        var sync = mock(RestaurantSearchSyncService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("app.search.sync.enabled=true", "app.search.sync.delay-ms=100")
                    .applyTo(context);
            context.registerBean(RestaurantSearchSyncService.class, () -> sync);
            context.register(
                    RestaurantSearchSyncConfiguration.class,
                    RestaurantSearchSyncJob.class,
                    RestaurantSearchSyncTrigger.class);
            context.refresh();
            verify(sync, timeout(3000).atLeastOnce()).processBatch();
        }
    }

    @Test
    void doesNotScheduleWhenDisabled() {
        var sync = mock(RestaurantSearchSyncService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("app.search.sync.enabled=false").applyTo(context);
            context.registerBean(RestaurantSearchSyncService.class, () -> sync);
            context.register(
                    RestaurantSearchSyncConfiguration.class,
                    RestaurantSearchSyncJob.class,
                    RestaurantSearchSyncTrigger.class);
            context.refresh();
            verifyNoInteractions(sync);
            org.assertj.core.api.Assertions.assertThat(
                            context.getBeansOfType(RestaurantSearchSyncJob.class))
                    .isEmpty();
        }
    }
}
