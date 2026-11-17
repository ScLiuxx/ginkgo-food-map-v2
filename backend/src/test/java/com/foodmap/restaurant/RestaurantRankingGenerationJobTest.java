package com.foodmap.restaurant;

import com.foodmap.restaurant.config.RestaurantRankingProperties;
import com.foodmap.restaurant.job.RestaurantRankingGenerationJob;
import com.foodmap.restaurant.service.RestaurantRankingGenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 实际运行独立调度线程，验证就绪门禁、失败后的后续轮次、关闭及配置开关。 */
class RestaurantRankingGenerationJobTest {
    private final RestaurantRankingGenerationService generation = mock(RestaurantRankingGenerationService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(JobConfiguration.class)
            .withBean(RestaurantRankingGenerationService.class, () -> generation);

    @Test
    void readyEventStartsAsynchronouslyOnceAndContextCloseStopsTheTask() throws Exception {
        var ran = new CountDownLatch(1);
        var thread = new AtomicReference<Thread>();
        when(generation.generate()).thenAnswer(invocation -> {
            thread.set(Thread.currentThread());
            ran.countDown();
            return true;
        });
        context.run(application -> {
            assertThat(application).hasSingleBean(RestaurantRankingGenerationJob.class);
            verifyNoInteractions(generation);
            var event = new ApplicationReadyEvent(new SpringApplication(JobConfiguration.class), new String[0],
                    application.getSourceApplicationContext(), Duration.ZERO);
            application.publishEvent(event);
            application.publishEvent(event);
            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get()).isNotSameAs(Thread.currentThread());
            assertThat(thread.get().getName()).startsWith("restaurant-ranking-generation-");
        });
        thread.get().join(5000);
        assertThat(thread.get().isAlive()).isFalse();
        verify(generation, times(1)).generate();
    }

    @Test
    void failedRoundDoesNotCancelFutureRoundsAndUsesCompletionDelay() {
        var second = new CountDownLatch(1);
        var calls = new AtomicInteger();
        long[] times = new long[2];
        when(generation.generate()).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                times[0] = System.nanoTime();
                throw new IllegalStateException("isolated failure");
            }
            times[1] = System.nanoTime();
            second.countDown();
            return true;
        });
        context.withPropertyValues("app.ranking.refresh-interval=50ms").run(application -> {
            application.getBean(RestaurantRankingGenerationJob.class).start();
            assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
            application.getBean(RestaurantRankingGenerationJob.class).stop();
            assertThat(times[1] - times[0]).isGreaterThanOrEqualTo(Duration.ofMillis(50).toNanos());
        });
    }

    @Test
    void disablingGenerationDoesNotCreateOrRunAutomaticTask() {
        context.withPropertyValues("app.ranking.generation-enabled=false").run(application -> {
            assertThat(application).doesNotHaveBean(RestaurantRankingGenerationJob.class);
            verifyNoInteractions(generation);
        });
    }

    @Test
    void shutdownBeforeReadyPreventsLateStartup() {
        context.run(application -> {
            var job = application.getBean(RestaurantRankingGenerationJob.class);
            job.stop();
            job.start();
            verifyNoInteractions(generation);
        });
    }

    @Test
    void invalidRefreshIntervalFailsConfigurationValidation() {
        for (String value : new String[] {"0ms", "-1s", "1ns"}) {
            context.withPropertyValues("app.ranking.refresh-interval=" + value)
                    .run(application -> assertThat(application).hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RestaurantRankingProperties.class)
    @Import(RestaurantRankingGenerationJob.class)
    static class JobConfiguration {
    }
}
